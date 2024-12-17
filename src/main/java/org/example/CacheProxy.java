package org.example;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class CacheProxy {
    private final Map<String, Object> inMemoryCache = new HashMap<>();
    private final Path rootDir;
    private final boolean defaultZip;

    public CacheProxy(Path rootDir, boolean defaultZip) {
        this.rootDir = rootDir;
        this.defaultZip = defaultZip;
        if (!Files.exists(rootDir)) {
            try {
                Files.createDirectories(rootDir);
            } catch (IOException e) {
                throw new RuntimeException("Cannot create root directory for cache: " + rootDir, e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T cache(T service) {
        Class<?> clazz = service.getClass();
        return (T) Proxy.newProxyInstance(clazz.getClassLoader(), clazz.getInterfaces(), new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                Cache cacheAnnotation = method.getAnnotation(Cache.class);
                if (cacheAnnotation == null) {
                    // метод не кэшируется
                    return method.invoke(service, args);
                }

                // Определяем ключ кэша
                String cacheKey = buildCacheKey(method, args, cacheAnnotation);

                // Сначала проверяем кэш
                if (cacheAnnotation.cacheType() == CacheType.IN_MEMORY) {
                    if (inMemoryCache.containsKey(cacheKey)) {
                        return inMemoryCache.get(cacheKey);
                    }
                } else {
                    // FILE cache
                    Object result = loadFromFile(cacheKey, cacheAnnotation);
                    if (result != null) {
                        return result;
                    }
                }

                // Вычисляем результат
                Object result = method.invoke(service, args);

                // Если результат - List, возможно нужно обрезать
                if (result instanceof List) {
                    int limit = cacheAnnotation.listLimit();
                    List<?> list = (List<?>) result;
                    if (list.size() > limit) {
                        result = list.subList(0, limit);
                    }
                }

                // Сохраняем в кэш
                if (cacheAnnotation.cacheType() == CacheType.IN_MEMORY) {
                    inMemoryCache.put(cacheKey, result);
                } else {
                    saveToFile(cacheKey, result, cacheAnnotation);
                }

                return result;
            }
        });
    }

    private String buildCacheKey(Method method, Object[] args, Cache cacheAnnotation) {
        String prefix = cacheAnnotation.fileNamePrefix().isEmpty() ? method.getName() : cacheAnnotation.fileNamePrefix();

        Class<?>[] identityBy = cacheAnnotation.identityBy();
        List<Object> identityArgs = new ArrayList<>();

        if (identityBy.length == 0) {
            // использовать все аргументы
            identityArgs.addAll(Arrays.asList(args));
        } else {
            // фильтруем по типам
            for (Object arg : args) {
                for (Class<?> cls : identityBy) {
                    if (arg != null && cls.isAssignableFrom(arg.getClass())) {
                        identityArgs.add(arg);
                    }
                }
            }
        }

        String keyPart = identityArgs.isEmpty() ? "no_args" : identityArgs.toString();
        return prefix + "_" + method.getName() + "_" + keyPart.hashCode();
    }

    private void saveToFile(String cacheKey, Object result, Cache cacheAnnotation) {
        Path filePath = rootDir.resolve(cacheKey + ".cache");
        try (OutputStream fos = Files.newOutputStream(filePath);
             OutputStream out = cacheAnnotation.zip() ? new GZIPOutputStream(fos) : fos;
             ObjectOutputStream oos = new ObjectOutputStream(out)) {

            if (!(result instanceof Serializable)) {
                throw new RuntimeException("Result of method is not serializable. " +
                        "Make return type Serializable or change cacheType to IN_MEMORY.");
            }

            oos.writeObject(result);
        } catch (IOException e) {
            throw new RuntimeException("Error saving cache to file: " + filePath, e);
        }
    }

    private Object loadFromFile(String cacheKey, Cache cacheAnnotation) {
        Path filePath = rootDir.resolve(cacheKey + ".cache");
        if (!Files.exists(filePath)) {
            return null;
        }
        try (InputStream fis = Files.newInputStream(filePath);
             InputStream in = cacheAnnotation.zip() ? new GZIPInputStream(fis) : fis;
             ObjectInputStream ois = new ObjectInputStream(in)) {
            return ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            // Ошибки десериализации - можно удалить файл
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException ex) {
                // игнорируем
            }
            return null;
        }
    }
}