package de.verdox.vpipeline.api;

import org.jetbrains.annotations.NotNull;
import org.reflections.Reflections;

import java.lang.reflect.InvocationTargetException;
import java.util.logging.Level;

public final class VNetwork {

    private static ConstructionService pipelineService;

    private VNetwork() {
    }

    public static ConstructionService getConstructionService() {
        return pipelineService;
    }

    static {
        setConstructionService(instantiateSingleton(ConstructionService.class));
    }

    private static void setConstructionService(@NotNull ConstructionService pipelineService) {
        if (VNetwork.pipelineService != null) {
            {
                throw new UnsupportedOperationException("Cannot redefine singleton PipelineService");
            }
        }
        NetworkLogger.getLogger().setLevel(Level.ALL);
        VNetwork.pipelineService = pipelineService;
    }

    private static <T> T instantiateSingleton(@NotNull Class<? extends T> type) {
        Reflections reflections = new Reflections("de.verdox.vpipeline.impl");
        Class<? extends T> pipelineServiceClass = reflections.getSubTypesOf(type).stream().findAny().orElse(null);
        try {
            if (pipelineServiceClass == null)
                throw new NullPointerException("Could not find service implementation class for " + type);
            return pipelineServiceClass.getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                 NoSuchMethodException e) {
            e.printStackTrace();
            return null;
        }
    }
}
