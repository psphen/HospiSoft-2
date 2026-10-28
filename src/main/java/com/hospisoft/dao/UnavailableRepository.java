package com.hospisoft.dao;

import com.hospisoft.db.DatabaseUnavailableException;
import java.lang.reflect.Proxy;

/**
 * Stand-in used when config.properties is missing or unusable: every repository method throws
 * {@link DatabaseUnavailableException}, so the UI shows the DB banner instead of crashing.
 */
public final class UnavailableRepository {

    private UnavailableRepository() {
    }

    public static <T> T create(Class<T> repositoryInterface) {
        Object proxy = Proxy.newProxyInstance(repositoryInterface.getClassLoader(),
            new Class<?>[] {repositoryInterface}, (p, method, args) -> {
                switch (method.getName()) {
                    case "toString":
                        return "Unavailable" + repositoryInterface.getSimpleName();
                    case "hashCode":
                        return System.identityHashCode(p);
                    case "equals":
                        return p == args[0];
                    default:
                        throw new DatabaseUnavailableException("Database is not configured", null);
                }
            });
        return repositoryInterface.cast(proxy);
    }
}
