package de.verdox.vpipeline.api.pipeline.datatypes.customtypes;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import de.verdox.vpipeline.api.NetworkLogger;
import de.verdox.vpipeline.api.pipeline.core.Pipeline;
import de.verdox.vpipeline.api.pipeline.core.PipelineLock;
import de.verdox.vpipeline.api.pipeline.datatypes.IPipelineData;
import de.verdox.vpipeline.api.util.AnnotationResolver;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public record DataReference<T extends IPipelineData>(Pipeline pipeline, Class<? extends T> type, UUID uuid) {
    public static <T extends IPipelineData> DataReference<T> of(Pipeline pipeline, Class<? extends T> type, UUID uuid) {
        return new DataReference<>(pipeline, type, uuid);
    }

    public CompletableFuture<Boolean> exists() {
        return pipeline.exist(type, uuid);
    }

    public CompletableFuture<Boolean> delete() {
        return pipeline.delete(type, uuid);
    }

    public CompletableFuture<PipelineLock<T>> loadOrCreate() {
        return pipeline.loadOrCreate(type, uuid);
    }

    public CompletableFuture<PipelineLock<T>> load() {
        return pipeline.load(type, uuid);
    }

    public <O> O get(Function<? super T, ? extends O> getter) {
        return loadOrCreate().join().getter(getter);
    }

    public CompletableFuture<Void> writeAsync(Consumer<T> writer) {
        var future = new CompletableFuture<Void>();
        loadOrCreate().whenComplete((tPipelineLock, throwable) -> {
            tPipelineLock.performWriteOperation(writer, true);
            future.complete(null);
        });
        return future;
    }

    public CompletableFuture<Void> runAsync(Consumer<T> writer) {
        var future = new CompletableFuture<Void>();
        loadOrCreate().whenComplete((tPipelineLock, throwable) -> {
            tPipelineLock.performReadOperation(writer);
            future.complete(null);
        });
        return future;
    }

    public <O> CompletableFuture<O> getAsync(Function<? super T, ? extends O> getter) {
        var future = new CompletableFuture<O>();
        loadOrCreate().whenComplete((tPipelineLock, throwable) -> future.complete(tPipelineLock.getter(getter)));
        return future;
    }

    @Nullable
    public <O> O getUnsafe(Function<? super T, ? extends O> getter) {
        var localObject = pipeline.getLocalCache().loadObject(type, uuid);
        if (localObject == null) {
            load();
            return null;
        }
        var localResult = getter.apply(localObject);
        if (localResult == null)
            load();
        return localResult;
    }

    public static class ReferenceAdapter extends TypeAdapter<DataReference<?>> {
        private final Pipeline pipeline;

        public ReferenceAdapter(Pipeline pipeline) {
            this.pipeline = pipeline;
        }

        @Override
        public void write(JsonWriter jsonWriter, DataReference<?> dataReference) throws IOException {
            var storageID = AnnotationResolver.getDataStorageIdentifier(dataReference.type());
            jsonWriter
                    .beginObject()
                    .name("uuid")
                    .value(dataReference.uuid().toString())
                    .name("type")
                    .value(storageID)
                    .endObject();
        }

        @Override
        public DataReference<?> read(JsonReader jsonReader) throws IOException {

            jsonReader.beginObject();
            String fieldName = null;
            UUID uuid = null;
            Class<? extends IPipelineData> type = null;

            while (jsonReader.hasNext() && (uuid == null || type == null)) {
                var token = jsonReader.peek();

                if (token.equals(JsonToken.NAME))
                    fieldName = jsonReader.nextName();
                else if ("uuid".equals(fieldName)) {
                    jsonReader.peek();
                    uuid = UUID.fromString(jsonReader.nextString());
                } else if ("type".equals(fieldName)) {
                    jsonReader.peek();
                    var dataStorageID = jsonReader.nextString();
                    type = pipeline.getDataRegistry().getTypeByStorageId(dataStorageID);
                    if (type == null) {
                        NetworkLogger.getLogger().warning("No type found for storageID " + dataStorageID);
                        return null;
                    }
                }
            }
            jsonReader.endObject();

            if (type == null) {
                NetworkLogger.getLogger().warning("Error while reading data reference. Type could not be found");
                return null;
            } else if (uuid == null) {
                NetworkLogger.getLogger().warning("Error while reading data reference. UUID could not be found");
                return null;
            }
            return new DataReference<>(pipeline, type, uuid);
        }
    }
}
