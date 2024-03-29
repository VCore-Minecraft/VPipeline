package de.verdox.vpipeline.impl.pipeline.core;

import com.google.gson.GsonBuilder;
import de.verdox.vpipeline.api.NetworkLogger;
import de.verdox.vpipeline.api.NetworkParticipant;
import de.verdox.vpipeline.api.pipeline.annotations.PipelineDataProperties;
import de.verdox.vpipeline.api.pipeline.core.Pipeline;
import de.verdox.vpipeline.api.pipeline.core.PipelineLock;
import de.verdox.vpipeline.api.pipeline.core.PipelineSynchronizer;
import de.verdox.vpipeline.api.pipeline.datatypes.DataRegistry;
import de.verdox.vpipeline.api.pipeline.datatypes.IPipelineData;
import de.verdox.vpipeline.api.pipeline.datatypes.SynchronizingService;
import de.verdox.vpipeline.api.pipeline.datatypes.customtypes.DataReference;
import de.verdox.vpipeline.api.pipeline.enums.PreloadStrategy;
import de.verdox.vpipeline.api.pipeline.parts.GlobalCache;
import de.verdox.vpipeline.api.pipeline.parts.GlobalStorage;
import de.verdox.vpipeline.api.pipeline.parts.LocalCache;
import de.verdox.vpipeline.api.util.AnnotationResolver;
import de.verdox.vpipeline.impl.pipeline.datatypes.DataRegistryImpl;
import de.verdox.vpipeline.impl.pipeline.parts.LocalCacheImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

public class PipelineImpl implements Pipeline {

    //TODO: Global Pipeline Events sent with messaging service

    private final GlobalStorage globalStorage;
    private final GlobalCache globalCache;
    private final LocalCache localCache;
    private final SynchronizingService synchronizingService;
    private final ExecutorService executorService;
    private final PipelineSynchronizerImpl pipelineSynchronizer;
    private final DataRegistryImpl dataRegistry;

    private NetworkParticipant networkParticipant;
    private boolean ready;
    private final Consumer<GsonBuilder> gsonBuilderConsumer;

    public PipelineImpl(@NotNull ExecutorService executorService, @Nullable GlobalCache globalCache, @Nullable GlobalStorage globalStorage, @Nullable SynchronizingService synchronizingService, @Nullable Consumer<GsonBuilder> gsonBuilderConsumer) {
        Objects.requireNonNull(executorService);
        this.gsonBuilderConsumer = gsonBuilderConsumer;
        this.executorService = executorService;
        this.globalStorage = globalStorage;
        this.globalCache = globalCache;
        this.localCache = new LocalCacheImpl();
        this.synchronizingService = synchronizingService;
        /*        this.executorService = Executors.newScheduledThreadPool(2, new DefaultThreadFactory("VPipeline-ThreadPool"));*/
        this.pipelineSynchronizer = new PipelineSynchronizerImpl(this);
        this.dataRegistry = new DataRegistryImpl(this);

        this.localCache
                .getAttachedPipeline()
                .attachPipeline(this);
        if (globalCache != null)
            this.globalCache
                    .getAttachedPipeline()
                    .attachPipeline(this);
        if (globalStorage != null)
            this.globalStorage
                    .getAttachedPipeline()
                    .attachPipeline(this);
        this.ready = true;
        NetworkLogger.info("Pipeline started");
    }

    public void setNetworkParticipant(NetworkParticipant networkParticipant) {
        this.networkParticipant = networkParticipant;
    }

    @Override
    public NetworkParticipant getNetworkParticipant() {
        return networkParticipant;
    }

    @Override
    public LocalCache getLocalCache() {
        return localCache;
    }

    @Override
    public @Nullable SynchronizingService getSynchronizingService() {
        return synchronizingService;
    }

    @Override
    public @NotNull PipelineSynchronizer getPipelineSynchronizer() {
        return pipelineSynchronizer;
    }

    @Override
    public @Nullable GlobalCache getGlobalCache() {
        return globalCache;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public @Nullable GlobalStorage getGlobalStorage() {
        return globalStorage;
    }

    @Override
    public @NotNull DataRegistry getDataRegistry() {
        return dataRegistry;
    }

    @Override
    public @NotNull GsonBuilder getGsonBuilder() {
        var builder = new GsonBuilder()
                .serializeNulls()
                .registerTypeHierarchyAdapter(DataReference.class, new DataReference.ReferenceAdapter(this));
        if (gsonBuilderConsumer != null)
            gsonBuilderConsumer.accept(builder);
        return builder;
    }


    @Override
    public void saveAll() {
        dataRegistry
                .getAllTypes()
                .forEach(type -> getLocalCache()
                        .getSavedUUIDs(type)
                        .forEach(uuid -> sync(type, uuid)));
    }

    @Override
    public void preloadAll() {
        dataRegistry
                .getAllTypes()
                .forEach(this::preloadData);
    }

    @Override
    public <T extends IPipelineData> @Nullable CompletableFuture<PipelineLock<T>> load(@NotNull Class<? extends T> dataClass, @NotNull UUID uuid, @Nullable Consumer<@NotNull T> loadCallback) {
        Objects.requireNonNull(dataClass, "dataClass can't be null");
        Objects.requireNonNull(uuid, "uuid can't be null");
        if (!getDataRegistry().isTypeRegistered(dataClass))
            throw new IllegalStateException("dataclass " + dataClass.getSimpleName() + " not registered in pipeline data registry");

        var future = new CompletableFuture<PipelineLock<T>>();
        executePipelineTask(future, () -> {
            var pipelineLock = createPipelineLock(dataClass, uuid);
            if (loadCallback == null) {
                pipelineLock.runOnReadLock(() -> {
                    var loadedData = load(dataClass, uuid, false);
                    if (loadedData == null)
                        future.cancel(true);
                });
            } else {
                pipelineLock.runOnWriteLock(() -> {
                    var loadedData = load(dataClass, uuid, false);
                    if (loadedData == null)
                        future.cancel(true);
                    else {
                        loadCallback.accept(loadedData);
                        pipelineSynchronizer.doSync(loadedData, true, () -> {
                        });
                    }
                });
            }

            future.complete((PipelineLock<T>) pipelineLock);
            return null;
        });
        return future.orTimeout(10, TimeUnit.SECONDS);
    }

    @Override
    public @NotNull <T extends IPipelineData> CompletableFuture<PipelineLock<T>> loadOrCreate(@NotNull Class<? extends T> dataClass, @NotNull UUID uuid, @Nullable Consumer<T> loadCallback) {
        Objects.requireNonNull(dataClass, "dataClass can't be null");
        Objects.requireNonNull(uuid, "uuid can't be null");
        if (!getDataRegistry().isTypeRegistered(dataClass))
            throw new IllegalStateException("dataclass " + dataClass.getSimpleName() + " not registered in pipeline data registry");

        var future = new CompletableFuture<PipelineLock<T>>();
        executePipelineTask(future, () -> {
            try {
                var pipelineLock = createPipelineLock(dataClass, uuid);
                pipelineLock.runOnWriteLock(() -> {
                    var loadedData = load(dataClass, uuid, true);
                    if (loadedData != null && loadCallback != null) {
                        loadCallback.accept(loadedData);
                        pipelineSynchronizer.doSync(loadedData, true, () -> {
                        });
                    }
                });
                future.complete((PipelineLock<T>) pipelineLock);
            } catch (Throwable e) {
                e.printStackTrace();
                future.completeExceptionally(e);
            }
            return null;
        });
        return future.orTimeout(10, TimeUnit.SECONDS);
    }

    @Override
    public @NotNull <T extends IPipelineData> CompletableFuture<Set<DataReference<T>>> loadAllData(@NotNull Class<? extends T> dataClass) {
        Objects.requireNonNull(dataClass, "dataClass can't be null");
        if (!getDataRegistry().isTypeRegistered(dataClass))
            throw new IllegalStateException("dataclass " + dataClass.getSimpleName() + " not registered in pipeline data registry");

        var future = new CompletableFuture<Set<DataReference<T>>>();
        executePipelineTask(future, () -> {
            //Syncing data
            if (getGlobalStorage() != null && AnnotationResolver.getDataProperties(dataClass).dataContext()
                                                                .isStorageAllowed()) {
                getGlobalStorage()
                        .getSavedUUIDs(dataClass)
                        .parallelStream()
                        .forEach(uuid -> {
                            var pipelineLock = createPipelineLock(dataClass, uuid);
                            pipelineLock.runOnWriteLock(() -> {
                                if (!localCache.dataExist(dataClass, uuid))
                                    pipelineSynchronizer.doSynchronize(PipelineSynchronizer.DataSourceType.GLOBAL_STORAGE, PipelineSynchronizer.DataSourceType.LOCAL, dataClass, uuid, null);
                            });
                        });

            }
            if (getGlobalCache() != null && AnnotationResolver.getDataProperties(dataClass).dataContext()
                                                              .isCacheAllowed()) {
                getGlobalCache()
                        .getSavedUUIDs(dataClass)
                        .parallelStream()
                        .forEach(uuid -> {
                            var pipelineLock = createPipelineLock(dataClass, uuid);
                            pipelineLock.runOnWriteLock(() -> {
                                if (!localCache.dataExist(dataClass, uuid))
                                    pipelineSynchronizer.doSynchronize(PipelineSynchronizer.DataSourceType.GLOBAL_CACHE, PipelineSynchronizer.DataSourceType.LOCAL, dataClass, uuid, null);
                            });
                        });
            }

            var set = new HashSet<DataReference<T>>();
            for (UUID savedUUID : getLocalCache().getSavedUUIDs(dataClass))
                set.add(createDataReference(dataClass, savedUUID));
            future.complete(set);
            return future;
        });
        return future.orTimeout(10, TimeUnit.SECONDS);
    }

    @Override
    public <T extends IPipelineData> CompletableFuture<Boolean> exist(@NotNull Class<? extends T> dataClass, @NotNull UUID uuid) {
        Objects.requireNonNull(dataClass, "dataClass can't be null");
        Objects.requireNonNull(uuid, "uuid can't be null");
        if (!getDataRegistry().isTypeRegistered(dataClass))
            throw new IllegalStateException("dataclass " + dataClass.getSimpleName() + " not registered in pipeline data registry");
        var future = new CompletableFuture<Boolean>();
        executePipelineTask(future, () -> {
            createPipelineLock(dataClass, uuid).runOnReadLock(() -> future.complete(checkExistence(dataClass, uuid)));
            return null;
        });
        return future.orTimeout(10, TimeUnit.SECONDS);
    }

    @Override
    public <T extends IPipelineData> CompletableFuture<Boolean> delete(@NotNull Class<? extends T> dataClass, @NotNull UUID uuid) {
        if (!getDataRegistry().isTypeRegistered(dataClass))
            throw new IllegalStateException("dataclass " + dataClass.getSimpleName() + " not registered in pipeline data registry");

        var future = new CompletableFuture<Boolean>();
        executePipelineTask(future, () -> {
            createPipelineLock(dataClass, uuid).runOnWriteLock(() -> {
                var deleted = getLocalCache().remove(dataClass, uuid);

                if (getSynchronizingService() != null) {
                    getSynchronizingService()
                            .getOrCreate(this, dataClass)
                            .pushRemoval(uuid, () -> {
                            });
                }

                if (getGlobalCache() != null && getGlobalCache().dataExist(dataClass, uuid))
                    deleted &= getGlobalCache().remove(dataClass, uuid);
                if (getGlobalStorage() != null && getGlobalStorage().dataExist(dataClass, uuid))
                    deleted &= getGlobalStorage().remove(dataClass, uuid);

                future.complete(deleted);
                if (AnnotationResolver.getDataProperties(dataClass).debugMode())
                    NetworkLogger.debug("Deleted: " + dataClass + " with " + uuid);
            });
            return null;
        });
        return future.orTimeout(10, TimeUnit.SECONDS);
    }

    private <T extends IPipelineData> T load(@NotNull Class<? extends T> dataClass, @NotNull UUID uuid, boolean createIfNotExist) {
        if (!getDataRegistry().isTypeRegistered(dataClass))
            throw new IllegalStateException("dataclass " + dataClass.getSimpleName() + " not registered in pipeline data registry");
        if (localCache.dataExist(dataClass, uuid)) {
            return localCache.loadObject(dataClass, uuid);
        } else if (globalCache != null && globalCache.dataExist(dataClass, uuid) && AnnotationResolver
                .getDataProperties(dataClass)
                .dataContext()
                .isCacheAllowed())
            pipelineSynchronizer.doSynchronize(PipelineSynchronizer.DataSourceType.GLOBAL_CACHE, PipelineSynchronizer.DataSourceType.LOCAL, dataClass, uuid, () -> {
                if (AnnotationResolver.getDataProperties(dataClass).debugMode())
                    NetworkLogger
                            .debug("CACHE -> Local | " + dataClass + " [" + uuid + "]");
            });
        else if (globalStorage != null && globalStorage.dataExist(dataClass, uuid) && AnnotationResolver
                .getDataProperties(dataClass)
                .dataContext()
                .isStorageAllowed())
            pipelineSynchronizer.doSynchronize(PipelineSynchronizer.DataSourceType.GLOBAL_STORAGE, PipelineSynchronizer.DataSourceType.LOCAL, dataClass, uuid, () -> {
                if (AnnotationResolver.getDataProperties(dataClass).debugMode())
                    NetworkLogger
                            .debug("GLOBAL -> Local | " + dataClass.getSimpleName() + " [" + uuid + "]");
            });
        else {
            if (!createIfNotExist)
                return null;
            if (AnnotationResolver.getDataProperties(dataClass).debugMode())
                NetworkLogger.debug("Creating new " + dataClass.getSimpleName() + " [" + uuid + "]");
            return createNewData(dataClass, uuid);
        }
        return localCache.loadObject(dataClass, uuid);
    }

    private <T extends IPipelineData> boolean checkExistence(@NotNull Class<? extends T> dataClass, @NotNull UUID uuid) {
        boolean localExist = getLocalCache().dataExist(dataClass, uuid);
        if (localExist) return true;

        if (getGlobalCache() != null) {
            boolean globalCacheExists = getGlobalCache().dataExist(dataClass, uuid);
            if (globalCacheExists) return true;
        }

        if (getGlobalStorage() != null) return getGlobalStorage().dataExist(dataClass, uuid);
        return false;
    }

    private <T extends IPipelineData> void sync(@NotNull Class<? extends T> type, UUID uuid) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(uuid);
        IPipelineData data = getLocalCache().loadObject(type, uuid);
        if (data == null) return;
        //TODO: Mark for Removal in Pipeline
/*        if (data.isMarkedForRemoval())
            return;*/
        data.cleanUp();
        pipelineSynchronizer.doSynchronize(PipelineSynchronizer.DataSourceType.LOCAL, PipelineSynchronizer.DataSourceType.GLOBAL_CACHE, type, uuid, null);
        pipelineSynchronizer.doSynchronize(PipelineSynchronizer.DataSourceType.LOCAL, PipelineSynchronizer.DataSourceType.GLOBAL_STORAGE, type, uuid, null);
        getLocalCache().remove(type, uuid);
    }

    private <S extends IPipelineData> void preloadData(Class<? extends S> type) {
        Objects.requireNonNull(type, "Dataclass can't be null");
        PipelineDataProperties dataProperties = AnnotationResolver.getDataProperties(type);
        PreloadStrategy preloadStrategy = dataProperties.preloadStrategy();

        if (!preloadStrategy.equals(PreloadStrategy.LOAD_BEFORE)) return;
        Set<UUID> alreadyLoaded = new HashSet<>();
        if (globalCache != null && dataProperties
                .dataContext()
                .isStorageAllowed())
            globalCache
                    .getSavedUUIDs(type)
                    .forEach(uuid -> {
                        if (pipelineSynchronizer.doSynchronize(PipelineSynchronizer.DataSourceType.GLOBAL_CACHE, PipelineSynchronizer.DataSourceType.LOCAL, type, uuid, null))
                            alreadyLoaded.add(uuid);
                    });
        if (globalStorage != null && dataProperties
                .dataContext()
                .isCacheAllowed())
            globalStorage
                    .getSavedUUIDs(type)
                    .stream()
                    .filter(uuid -> !alreadyLoaded.contains(uuid))
                    .forEach(uuid -> pipelineSynchronizer.doSynchronize(PipelineSynchronizer.DataSourceType.GLOBAL_STORAGE, PipelineSynchronizer.DataSourceType.LOCAL, type, uuid, null));
    }

    private <T extends IPipelineData> T createNewData(@NotNull Class<? extends T> dataClass, @NotNull UUID uuid) {
        Objects.requireNonNull(dataClass, "Dataclass can't be null");
        Objects.requireNonNull(uuid, "UUID can't be null");
        if (AnnotationResolver.getDataProperties(dataClass).debugMode())
            NetworkLogger
                    .debug("[Pipeline] Creating new data of type: " + dataClass.getSimpleName() + " [" + uuid + "]");
        T pipelineData = localCache.instantiateData(dataClass, uuid);
        pipelineData.loadDependentData();
        pipelineData.onCreate();
        localCache.saveObject(pipelineData);

        if (AnnotationResolver
                .getDataProperties(dataClass)
                .dataContext()
                .isCacheAllowed())
            pipelineSynchronizer.doSynchronize(PipelineSynchronizer.DataSourceType.LOCAL, PipelineSynchronizer.DataSourceType.GLOBAL_CACHE, dataClass, uuid, null);
        if (AnnotationResolver
                .getDataProperties(dataClass)
                .dataContext()
                .isStorageAllowed())
            pipelineSynchronizer.doSynchronize(PipelineSynchronizer.DataSourceType.LOCAL, PipelineSynchronizer.DataSourceType.GLOBAL_STORAGE, dataClass, uuid, null);

        if (getSynchronizingService() != null)
            getSynchronizingService()
                    .getOrCreate(this, dataClass)
                    .pushCreation(pipelineData, null);

        return pipelineData;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    @Override
    public synchronized void shutdown() {
        if (!this.ready) {
            NetworkLogger.info("Pipeline is already offline");
            return;
        }
        this.ready = false;

        NetworkLogger.info("Saving all data");
        saveAll();

        NetworkLogger.info("Shutting down pipeline synchronizer");
        getPipelineSynchronizer().shutdown();

        NetworkLogger.info("Shutting down data providers");

        if (getGlobalStorage() != null)
            getGlobalStorage().shutdown();
        if (getGlobalCache() != null)
            getGlobalCache().shutdown();
        if (getSynchronizingService() != null)
            getSynchronizingService().shutdown();
        getLocalCache().shutdown();
        NetworkLogger.info("Pipeline offline");
    }

    private void executePipelineTask(CompletableFuture<?> future, Callable<?> callable) {
        if (executorService.isShutdown() || executorService.isTerminated()) {
            var e = new IllegalStateException("ExecutorService was shutted down.");
            e.printStackTrace();
            future.completeExceptionally(e);
            throw e;
        }
        if (!ready)
            future.cancel(true);
        else
            executorService.submit(() -> {
                try {
                    callable.call();
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            });
    }

    <T extends IPipelineData> PipelineLock<T> createPipelineLock(@NotNull T data) {
        return (PipelineLock<T>) createPipelineLock(data.getClass(), data.getObjectUUID());
    }


    @Override
    public <T extends IPipelineData> @NotNull PipelineLock<T> createPipelineLock(@NotNull Class<? extends T> dataClass, @NotNull UUID uuid) {
        Objects.requireNonNull(dataClass, "Dataclass can't be null");
        Objects.requireNonNull(uuid, "UUID can't be null");

        Lock readLock;
        Lock writeLock;

        if (getGlobalCache() != null) {
            readLock = getGlobalCache().acquireGlobalObjectReadLock(dataClass, uuid);
            writeLock = getGlobalCache().acquireGlobalObjectWriteLock(dataClass, uuid);
            Objects.requireNonNull(readLock, "GlobalCache is enabled but does not have a global read lock. Please inform the dev of this Cache implementation");
            Objects.requireNonNull(writeLock, "GlobalCache is enabled but does not have a global write lock. Please inform the dev of this Cache implementation");
        } else {
            //TODO: Reentrant Locks für einzelne Datenklassen und UUIds die auch wieder gelöscht werden onDelete. So gibt es wenigstens lokale locks.
            readLock = new DummyLock();
            writeLock = new DummyLock();
        }
        return new PipelineLockImpl<>(this, pipelineSynchronizer, dataClass, uuid, readLock, writeLock);
    }

    @Override
    public @NotNull <T extends IPipelineData> DataReference<T> createDataReference(@NotNull Class<? extends T> dataClass, @NotNull UUID uuid) {
        return DataReference.of(this, dataClass, uuid);
    }

    @Override
    public <T extends IPipelineData> CompletableFuture<Boolean> saveAndRemoveFromLocalCache(@NotNull Class<? extends T> dataClass, @NotNull UUID uuid) {
        var future = new CompletableFuture<Boolean>();
        executePipelineTask(future, () -> {
            createPipelineLock(dataClass, uuid).runOnWriteLock(() -> {
                pipelineSynchronizer.doSync(dataClass, uuid, true, () -> future.complete(getLocalCache().remove(dataClass, uuid)));
            });
            return null;
        });
        return future;
    }

    static class DummyLock implements Lock {

        @Override
        public void lock() {

        }

        @Override
        public void lockInterruptibly() throws InterruptedException {

        }

        @Override
        public boolean tryLock() {
            return false;
        }

        @Override
        public boolean tryLock(long time, @NotNull TimeUnit unit) throws InterruptedException {
            return false;
        }

        @Override
        public void unlock() {

        }

        @NotNull
        @Override
        public Condition newCondition() {
            return null;
        }
    }
}
