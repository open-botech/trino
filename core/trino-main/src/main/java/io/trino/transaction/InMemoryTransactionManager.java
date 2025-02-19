/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.transaction;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.concurrent.BoundedExecutor;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.trino.NotInTransactionException;
import io.trino.connector.CatalogName;
import io.trino.metadata.Catalog;
import io.trino.metadata.Catalog.SecurityManagement;
import io.trino.metadata.CatalogManager;
import io.trino.metadata.CatalogMetadata;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.transaction.IsolationLevel;
import org.joda.time.DateTime;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.Futures.nonCancellationPropagating;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.concurrent.MoreFutures.addExceptionCallback;
import static io.trino.spi.StandardErrorCode.AUTOCOMMIT_WRITE_CONFLICT;
import static io.trino.spi.StandardErrorCode.MULTI_CATALOG_WRITE_CONFLICT;
import static io.trino.spi.StandardErrorCode.NOT_FOUND;
import static io.trino.spi.StandardErrorCode.READ_ONLY_VIOLATION;
import static io.trino.spi.StandardErrorCode.TRANSACTION_ALREADY_ABORTED;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;

@ThreadSafe
public class InMemoryTransactionManager
        implements TransactionManager
{
    private static final Logger log = Logger.get(InMemoryTransactionManager.class);

    private final Duration idleTimeout;
    private final int maxFinishingConcurrency;

    private final ConcurrentMap<TransactionId, TransactionMetadata> transactions = new ConcurrentHashMap<>();
    private final CatalogManager catalogManager;
    private final Executor finishingExecutor;

    private InMemoryTransactionManager(Duration idleTimeout, int maxFinishingConcurrency, CatalogManager catalogManager, Executor finishingExecutor)
    {
        this.catalogManager = catalogManager;
        requireNonNull(idleTimeout, "idleTimeout is null");
        checkArgument(maxFinishingConcurrency > 0, "maxFinishingConcurrency must be at least 1");
        requireNonNull(finishingExecutor, "finishingExecutor is null");

        this.idleTimeout = idleTimeout;
        this.maxFinishingConcurrency = maxFinishingConcurrency;
        this.finishingExecutor = finishingExecutor;
    }

    public static TransactionManager create(
            TransactionManagerConfig config,
            ScheduledExecutorService idleCheckExecutor,
            CatalogManager catalogManager,
            Executor finishingExecutor)
    {
        InMemoryTransactionManager transactionManager = new InMemoryTransactionManager(config.getIdleTimeout(), config.getMaxFinishingConcurrency(), catalogManager, finishingExecutor);
        transactionManager.scheduleIdleChecks(config.getIdleCheckInterval(), idleCheckExecutor);
        return transactionManager;
    }

    public static TransactionManager createTestTransactionManager()
    {
        return createTestTransactionManager(new CatalogManager());
    }

    public static TransactionManager createTestTransactionManager(CatalogManager catalogManager)
    {
        // No idle checks needed
        return new InMemoryTransactionManager(new Duration(1, TimeUnit.DAYS), 1, catalogManager, directExecutor());
    }

    private void scheduleIdleChecks(Duration idleCheckInterval, ScheduledExecutorService idleCheckExecutor)
    {
        idleCheckExecutor.scheduleWithFixedDelay(() -> {
            try {
                cleanUpExpiredTransactions();
            }
            catch (Throwable t) {
                log.error(t, "Unexpected exception while cleaning up expired transactions");
            }
        }, idleCheckInterval.toMillis(), idleCheckInterval.toMillis(), MILLISECONDS);
    }

    private synchronized void cleanUpExpiredTransactions()
    {
        Iterator<Entry<TransactionId, TransactionMetadata>> iterator = transactions.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<TransactionId, TransactionMetadata> entry = iterator.next();
            if (entry.getValue().isExpired(idleTimeout)) {
                iterator.remove();
                log.info("Removing expired transaction: %s", entry.getKey());
                entry.getValue().asyncAbort();
            }
        }
    }

    @Override
    public boolean transactionExists(TransactionId transactionId)
    {
        return tryGetTransactionMetadata(transactionId).isPresent();
    }

    @Override
    public TransactionInfo getTransactionInfo(TransactionId transactionId)
    {
        return getTransactionMetadata(transactionId).getTransactionInfo();
    }

    @Override
    public List<TransactionInfo> getAllTransactionInfos()
    {
        return transactions.values().stream()
                .map(TransactionMetadata::getTransactionInfo)
                .collect(toImmutableList());
    }

    @Override
    public TransactionId beginTransaction(boolean autoCommitContext)
    {
        return beginTransaction(DEFAULT_ISOLATION, DEFAULT_READ_ONLY, autoCommitContext);
    }

    @Override
    public TransactionId beginTransaction(IsolationLevel isolationLevel, boolean readOnly, boolean autoCommitContext)
    {
        TransactionId transactionId = TransactionId.create();
        BoundedExecutor executor = new BoundedExecutor(finishingExecutor, maxFinishingConcurrency);
        TransactionMetadata transactionMetadata = new TransactionMetadata(transactionId, isolationLevel, readOnly, autoCommitContext, catalogManager, executor);
        checkState(transactions.put(transactionId, transactionMetadata) == null, "Duplicate transaction ID: %s", transactionId);
        return transactionId;
    }

    @Override
    public Map<String, CatalogName> getCatalogNames(TransactionId transactionId)
    {
        return getTransactionMetadata(transactionId).getCatalogNames();
    }

    @Override
    public Optional<CatalogMetadata> getOptionalCatalogMetadata(TransactionId transactionId, String catalogName)
    {
        TransactionMetadata transactionMetadata = getTransactionMetadata(transactionId);
        return transactionMetadata.getConnectorId(catalogName)
                .map(transactionMetadata::getTransactionCatalogMetadata);
    }

    @Override
    public CatalogMetadata getCatalogMetadata(TransactionId transactionId, CatalogName catalogName)
    {
        return getTransactionMetadata(transactionId).getTransactionCatalogMetadata(catalogName);
    }

    @Override
    public CatalogMetadata getCatalogMetadataForWrite(TransactionId transactionId, CatalogName catalogName)
    {
        CatalogMetadata catalogMetadata = getCatalogMetadata(transactionId, catalogName);
        checkConnectorWrite(transactionId, catalogName);
        return catalogMetadata;
    }

    @Override
    public CatalogMetadata getCatalogMetadataForWrite(TransactionId transactionId, String catalogName)
    {
        TransactionMetadata transactionMetadata = getTransactionMetadata(transactionId);

        // there is no need to ask for a connector specific id since the overlay connectors are read only
        CatalogName catalog = transactionMetadata.getConnectorId(catalogName)
                .orElseThrow(() -> new TrinoException(NOT_FOUND, "Catalog does not exist: " + catalogName));

        return getCatalogMetadataForWrite(transactionId, catalog);
    }

    @Override
    public ConnectorTransactionHandle getConnectorTransaction(TransactionId transactionId, CatalogName catalogName)
    {
        return getCatalogMetadata(transactionId, catalogName).getTransactionHandleFor(catalogName);
    }

    private void checkConnectorWrite(TransactionId transactionId, CatalogName catalogName)
    {
        getTransactionMetadata(transactionId).checkConnectorWrite(catalogName);
    }

    @Override
    public void checkAndSetActive(TransactionId transactionId)
    {
        TransactionMetadata metadata = getTransactionMetadata(transactionId);
        metadata.checkOpenTransaction();
        metadata.setActive();
    }

    @Override
    public void trySetActive(TransactionId transactionId)
    {
        tryGetTransactionMetadata(transactionId).ifPresent(TransactionMetadata::setActive);
    }

    @Override
    public void trySetInactive(TransactionId transactionId)
    {
        tryGetTransactionMetadata(transactionId).ifPresent(TransactionMetadata::setInactive);
    }

    private TransactionMetadata getTransactionMetadata(TransactionId transactionId)
    {
        TransactionMetadata transactionMetadata = transactions.get(transactionId);
        if (transactionMetadata == null) {
            throw new NotInTransactionException(transactionId);
        }
        return transactionMetadata;
    }

    private Optional<TransactionMetadata> tryGetTransactionMetadata(TransactionId transactionId)
    {
        return Optional.ofNullable(transactions.get(transactionId));
    }

    private ListenableFuture<TransactionMetadata> removeTransactionMetadataAsFuture(TransactionId transactionId)
    {
        TransactionMetadata transactionMetadata = transactions.remove(transactionId);
        if (transactionMetadata == null) {
            return immediateFailedFuture(new NotInTransactionException(transactionId));
        }
        return immediateFuture(transactionMetadata);
    }

    @Override
    public ListenableFuture<Void> asyncCommit(TransactionId transactionId)
    {
        return nonCancellationPropagating(Futures.transformAsync(removeTransactionMetadataAsFuture(transactionId), TransactionMetadata::asyncCommit, directExecutor()));
    }

    @Override
    public ListenableFuture<Void> asyncAbort(TransactionId transactionId)
    {
        return nonCancellationPropagating(Futures.transformAsync(removeTransactionMetadataAsFuture(transactionId), TransactionMetadata::asyncAbort, directExecutor()));
    }

    @Override
    public void fail(TransactionId transactionId)
    {
        // Mark transaction as failed, but don't remove it.
        tryGetTransactionMetadata(transactionId).ifPresent(TransactionMetadata::asyncAbort);
    }

    private static <T> ListenableFuture<Void> asVoid(ListenableFuture<T> future)
    {
        return Futures.transform(future, v -> null, directExecutor());
    }

    @ThreadSafe
    private static class TransactionMetadata
    {
        private final DateTime createTime = DateTime.now();
        private final CatalogManager catalogManager;
        private final TransactionId transactionId;
        private final IsolationLevel isolationLevel;
        private final boolean readOnly;
        private final boolean autoCommitContext;
        @GuardedBy("this")
        private final Map<CatalogName, ConnectorTransactionMetadata> connectorIdToMetadata = new ConcurrentHashMap<>();
        @GuardedBy("this")
        private final AtomicReference<CatalogName> writtenConnectorId = new AtomicReference<>();
        private final Executor finishingExecutor;
        private final AtomicReference<Boolean> completedSuccessfully = new AtomicReference<>();
        private final AtomicReference<Long> idleStartTime = new AtomicReference<>();

        @GuardedBy("this")
        private final Map<String, Optional<Catalog>> catalogByName = new ConcurrentHashMap<>();
        @GuardedBy("this")
        private final Map<CatalogName, Catalog> catalogsByName = new ConcurrentHashMap<>();
        @GuardedBy("this")
        private final Map<CatalogName, CatalogMetadata> catalogMetadata = new ConcurrentHashMap<>();

        public TransactionMetadata(
                TransactionId transactionId,
                IsolationLevel isolationLevel,
                boolean readOnly,
                boolean autoCommitContext,
                CatalogManager catalogManager,
                Executor finishingExecutor)
        {
            this.transactionId = requireNonNull(transactionId, "transactionId is null");
            this.isolationLevel = requireNonNull(isolationLevel, "isolationLevel is null");
            this.readOnly = readOnly;
            this.autoCommitContext = autoCommitContext;
            this.catalogManager = requireNonNull(catalogManager, "catalogManager is null");
            this.finishingExecutor = requireNonNull(finishingExecutor, "finishingExecutor is null");
        }

        public void setActive()
        {
            idleStartTime.set(null);
        }

        public void setInactive()
        {
            idleStartTime.set(System.nanoTime());
        }

        public boolean isExpired(Duration idleTimeout)
        {
            Long idleStartTime = this.idleStartTime.get();
            return idleStartTime != null && Duration.nanosSince(idleStartTime).compareTo(idleTimeout) > 0;
        }

        public void checkOpenTransaction()
        {
            Boolean completedStatus = this.completedSuccessfully.get();
            if (completedStatus != null) {
                if (completedStatus) {
                    // Should not happen normally
                    throw new IllegalStateException("Current transaction already committed");
                }
                else {
                    throw new TrinoException(TRANSACTION_ALREADY_ABORTED, "Current transaction is aborted, commands ignored until end of transaction block");
                }
            }
        }

        private synchronized Map<String, CatalogName> getCatalogNames()
        {
            // todo if repeatable read, this must be recorded
            Map<String, CatalogName> catalogNames = new HashMap<>();
            catalogByName.values().stream()
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .forEach(catalog -> catalogNames.put(catalog.getCatalogName(), catalog.getConnectorCatalogName()));

            catalogManager.getCatalogs().stream()
                    .forEach(catalog -> catalogNames.putIfAbsent(catalog.getCatalogName(), catalog.getConnectorCatalogName()));

            return ImmutableMap.copyOf(catalogNames);
        }

        private synchronized Optional<CatalogName> getConnectorId(String catalogName)
        {
            Optional<Catalog> catalog = catalogByName.get(catalogName);
            if (catalog == null) {
                catalog = catalogManager.getCatalog(catalogName);
                catalogByName.put(catalogName, catalog);
                if (catalog.isPresent()) {
                    registerCatalog(catalog.get());
                }
            }
            return catalog.map(Catalog::getConnectorCatalogName);
        }

        private synchronized void registerCatalog(Catalog catalog)
        {
            catalogsByName.put(catalog.getConnectorCatalogName(), catalog);
            catalogsByName.put(catalog.getInformationSchemaId(), catalog);
            catalogsByName.put(catalog.getSystemTablesId(), catalog);
        }

        private synchronized CatalogMetadata getTransactionCatalogMetadata(CatalogName catalogName)
        {
            checkOpenTransaction();

            CatalogMetadata catalogMetadata = this.catalogMetadata.get(catalogName);
            if (catalogMetadata == null) {
                Catalog catalog = catalogsByName.get(catalogName);
                verifyNotNull(catalog, "Unknown catalog: %s", catalogName);
                Connector connector = catalog.getConnector(catalogName);

                ConnectorTransactionMetadata metadata = createConnectorTransactionMetadata(catalog.getConnectorCatalogName(), catalog);
                ConnectorTransactionMetadata informationSchema = createConnectorTransactionMetadata(catalog.getInformationSchemaId(), catalog);
                ConnectorTransactionMetadata systemTables = createConnectorTransactionMetadata(catalog.getSystemTablesId(), catalog);

                catalogMetadata = new CatalogMetadata(
                        metadata.getCatalogName(),
                        metadata.getConnectorMetadata(),
                        metadata.getTransactionHandle(),
                        informationSchema.getCatalogName(),
                        informationSchema.getConnectorMetadata(),
                        informationSchema.getTransactionHandle(),
                        systemTables.getCatalogName(),
                        systemTables.getConnectorMetadata(),
                        systemTables.getTransactionHandle(),
                        metadata.getSecurityManagement(),
                        connector.getCapabilities());

                this.catalogMetadata.put(catalog.getConnectorCatalogName(), catalogMetadata);
                this.catalogMetadata.put(catalog.getInformationSchemaId(), catalogMetadata);
                this.catalogMetadata.put(catalog.getSystemTablesId(), catalogMetadata);
            }
            return catalogMetadata;
        }

        public synchronized ConnectorTransactionMetadata createConnectorTransactionMetadata(CatalogName catalogName, Catalog catalog)
        {
            Connector connector = catalog.getConnector(catalogName);
            ConnectorTransactionMetadata transactionMetadata = new ConnectorTransactionMetadata(catalogName, connector, beginTransaction(connector), catalog.getSecurityManagement());
            checkState(connectorIdToMetadata.put(catalogName, transactionMetadata) == null);
            return transactionMetadata;
        }

        private ConnectorTransactionHandle beginTransaction(Connector connector)
        {
            if (connector instanceof InternalConnector) {
                return ((InternalConnector) connector).beginTransaction(transactionId, isolationLevel, readOnly);
            }
            else {
                return connector.beginTransaction(isolationLevel, readOnly);
            }
        }

        public synchronized void checkConnectorWrite(CatalogName catalogName)
        {
            checkOpenTransaction();
            ConnectorTransactionMetadata transactionMetadata = connectorIdToMetadata.get(catalogName);
            checkArgument(transactionMetadata != null, "Cannot record write for connector not part of transaction");
            if (readOnly) {
                throw new TrinoException(READ_ONLY_VIOLATION, "Cannot execute write in a read-only transaction");
            }
            if (!writtenConnectorId.compareAndSet(null, catalogName) && !writtenConnectorId.get().equals(catalogName)) {
                throw new TrinoException(MULTI_CATALOG_WRITE_CONFLICT, "Multi-catalog writes not supported in a single transaction. Already wrote to catalog " + writtenConnectorId.get());
            }
            if (transactionMetadata.isSingleStatementWritesOnly() && !autoCommitContext) {
                throw new TrinoException(AUTOCOMMIT_WRITE_CONFLICT, "Catalog " + catalogName + " only supports writes using autocommit");
            }
        }

        public synchronized ListenableFuture<Void> asyncCommit()
        {
            if (!completedSuccessfully.compareAndSet(null, true)) {
                if (completedSuccessfully.get()) {
                    // Already done
                    return immediateVoidFuture();
                }
                // Transaction already aborted
                return immediateFailedFuture(new TrinoException(TRANSACTION_ALREADY_ABORTED, "Current transaction has already been aborted"));
            }

            CatalogName writeCatalogName = this.writtenConnectorId.get();
            if (writeCatalogName == null) {
                ListenableFuture<Void> future = asVoid(Futures.allAsList(connectorIdToMetadata.values().stream()
                        .map(transactionMetadata -> Futures.submit(transactionMetadata::commit, finishingExecutor))
                        .collect(toList())));
                addExceptionCallback(future, throwable -> {
                    abortInternal();
                    log.error(throwable, "Read-only connector should not throw exception on commit");
                });
                return nonCancellationPropagating(future);
            }

            Supplier<ListenableFuture<Void>> commitReadOnlyConnectors = () -> {
                List<ListenableFuture<Void>> futures = connectorIdToMetadata.entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(writeCatalogName))
                        .map(Entry::getValue)
                        .map(transactionMetadata -> Futures.submit(transactionMetadata::commit, finishingExecutor))
                        .collect(toList());
                ListenableFuture<Void> future = asVoid(Futures.allAsList(futures));
                addExceptionCallback(future, throwable -> log.error(throwable, "Read-only connector should not throw exception on commit"));
                return future;
            };

            ConnectorTransactionMetadata writeConnector = connectorIdToMetadata.get(writeCatalogName);
            ListenableFuture<Void> commitFuture = Futures.submit(writeConnector::commit, finishingExecutor);
            ListenableFuture<Void> readOnlyCommitFuture = Futures.transformAsync(commitFuture, ignored -> commitReadOnlyConnectors.get(), directExecutor());
            addExceptionCallback(readOnlyCommitFuture, this::abortInternal);
            return nonCancellationPropagating(readOnlyCommitFuture);
        }

        public synchronized ListenableFuture<Void> asyncAbort()
        {
            if (!completedSuccessfully.compareAndSet(null, false)) {
                if (completedSuccessfully.get()) {
                    // Should not happen normally
                    return immediateFailedFuture(new IllegalStateException("Current transaction already committed"));
                }
                // Already done
                return immediateVoidFuture();
            }
            return abortInternal();
        }

        private synchronized ListenableFuture<Void> abortInternal()
        {
            // the callbacks in statement performed on another thread so are safe
            List<ListenableFuture<Void>> futures = connectorIdToMetadata.values().stream()
                    .map(connection -> Futures.submit(() -> safeAbort(connection), finishingExecutor))
                    .collect(toList());
            ListenableFuture<Void> future = asVoid(Futures.allAsList(futures));
            return nonCancellationPropagating(future);
        }

        private static void safeAbort(ConnectorTransactionMetadata connection)
        {
            try {
                connection.abort();
            }
            catch (Exception e) {
                log.error(e, "Connector threw exception on abort");
            }
        }

        public TransactionInfo getTransactionInfo()
        {
            Duration idleTime = Optional.ofNullable(idleStartTime.get())
                    .map(Duration::nanosSince)
                    .orElse(new Duration(0, MILLISECONDS));

            // dereferencing this field is safe because the field is atomic
            @SuppressWarnings("FieldAccessNotGuarded") Optional<CatalogName> writtenConnectorId = Optional.ofNullable(this.writtenConnectorId.get());

            // copying the key set is safe here because the map is concurrent
            @SuppressWarnings("FieldAccessNotGuarded") List<CatalogName> catalogNames = ImmutableList.copyOf(connectorIdToMetadata.keySet());

            return new TransactionInfo(transactionId, isolationLevel, readOnly, autoCommitContext, createTime, idleTime, catalogNames, writtenConnectorId);
        }

        private static class ConnectorTransactionMetadata
        {
            private final CatalogName catalogName;
            private final Connector connector;
            private final ConnectorTransactionHandle transactionHandle;
            private final SecurityManagement securityManagement;
            private final ConnectorMetadata connectorMetadata;
            private final AtomicBoolean finished = new AtomicBoolean();

            public ConnectorTransactionMetadata(
                    CatalogName catalogName,
                    Connector connector,
                    ConnectorTransactionHandle transactionHandle,
                    SecurityManagement securityManagement)
            {
                this.catalogName = requireNonNull(catalogName, "catalogName is null");
                this.connector = requireNonNull(connector, "connector is null");
                this.transactionHandle = requireNonNull(transactionHandle, "transactionHandle is null");
                this.securityManagement = requireNonNull(securityManagement, "securityManagement is null");
                this.connectorMetadata = connector.getMetadata(transactionHandle);
            }

            public CatalogName getCatalogName()
            {
                return catalogName;
            }

            public boolean isSingleStatementWritesOnly()
            {
                return connector.isSingleStatementWritesOnly();
            }

            public SecurityManagement getSecurityManagement()
            {
                return securityManagement;
            }

            public synchronized ConnectorMetadata getConnectorMetadata()
            {
                checkState(!finished.get(), "Already finished");
                return connectorMetadata;
            }

            public ConnectorTransactionHandle getTransactionHandle()
            {
                checkState(!finished.get(), "Already finished");
                return transactionHandle;
            }

            public void commit()
            {
                if (finished.compareAndSet(false, true)) {
                    connector.commit(transactionHandle);
                }
            }

            public void abort()
            {
                if (finished.compareAndSet(false, true)) {
                    connector.rollback(transactionHandle);
                }
            }
        }
    }
}
