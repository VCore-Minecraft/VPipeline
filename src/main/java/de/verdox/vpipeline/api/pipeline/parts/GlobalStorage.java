package de.verdox.vpipeline.api.pipeline.parts;

import com.zaxxer.hikari.HikariDataSource;
import de.verdox.vpipeline.api.modules.json.JsonFileStorage;
import de.verdox.vpipeline.api.modules.mongo.MongoDBStorage;
import de.verdox.vpipeline.api.modules.sql.mysql.MySQLStorage;
import de.verdox.vpipeline.api.pipeline.datatypes.IPipelineData;
import de.verdox.vpipeline.api.util.AnnotationResolver;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

/**
 * @version 1.0
 * @Author: Lukas Jonsson (Verdox)
 * @date 18.06.2022 11:14
 */
public interface GlobalStorage extends DataProvider {
    default String getSuffix(@NotNull Class<? extends IPipelineData> dataClass) {
        Objects.requireNonNull(dataClass, "dataClass can't be null!");
        return AnnotationResolver.getDataStorageIdentifier(dataClass);
    }

    default String getStoragePath(@NotNull Class<? extends IPipelineData> dataClass, @NotNull String suffix, @NotNull String separator) {
        Objects.requireNonNull(dataClass, "dataClass can't be null!");
        Objects.requireNonNull(suffix, "suffix can't be null!");
        return AnnotationResolver.getDataStorageClassifier(dataClass) + separator + suffix;
    }

    static GlobalStorage buildMongoDBStorage(String host, String database, int port, String user, String password) {
        return new MongoDBStorage(host, database, port, user, password, "");
    }

    static GlobalStorage buildMongoDBStorage(String url) {
        return new MongoDBStorage("", "", 0, "", "", url);
    }

    static GlobalStorage buildJsonStorage(Path path) {
        return new JsonFileStorage(path);
    }

    static GlobalStorage buildSQLStorage(HikariDataSource hikariDataSource) {
        return new MySQLStorage(hikariDataSource);
    }
}