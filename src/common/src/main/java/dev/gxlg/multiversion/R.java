package dev.gxlg.multiversion;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

@SuppressWarnings({ "unused" })
public class R {
    private static final Map<ClassLoader, Map<Integer, Class<?>>> clazzCache = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<ClassLoader, Map<Integer, MethodHandle>> constructorsCache = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<ClassLoader, Map<Integer, StoredMethod>> methodsCache = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<ClassLoader, Map<Integer, StoredField>> fieldsCache = Collections.synchronizedMap(new WeakHashMap<>());

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final MethodType STATIC_METHOD_TYPE = MethodType.methodType(Object.class, Object[].class);

    private static final MethodType METHOD_TYPE = MethodType.methodType(Object.class, Object.class, Object[].class);

    private static final MethodType CONSTRUCTOR_TYPE = MethodType.methodType(Object.class, Object[].class);

    private static <T> T cache(Map<ClassLoader, Map<Integer, T>> cache, Class<?> base, Class<?>[] types, String[] names, Supplier<T> supplier) {
        Map<Integer, T> cacheMap = cache.computeIfAbsent(Thread.currentThread().getContextClassLoader(), cl -> new HashMap<>());
        return cacheMap.computeIfAbsent(Objects.hash(base, Arrays.hashCode(types), Arrays.hashCode(names)), i -> supplier.get());
    }

    public static RClass clz(String names) {
        return new RClass(names);
    }

    public static RClass clz(Class<?> clz) {
        return new RClass(clz);
    }

    public static boolean methodMatches(Method method, Class<?>... types) {
        Class<?> returnType = types[0];
        Class<?>[] methodParams = method.getParameterTypes();
        if (types.length - 1 != methodParams.length) {
            return false;
        }
        if (!returnType.isAssignableFrom(method.getReturnType())) {
            return false;
        }
        for (int i = 0; i < types.length - 1; i++) {
            if (!methodParams[i].isAssignableFrom(types[i + 1])) {
                return false;
            }
        }
        return true;
    }

    public static boolean fieldMatches(Field field, Class<?> fieldType) {
        return fieldType.isAssignableFrom(field.getType());
    }

    public interface StoredMethod {
        Object invk(Object instance, Object[] args);

        static StoredMethod of(int args, boolean isInstance, MethodHandle method) {
            if (isInstance) {
                return new Instance(args, method);
            } else {
                return new Static(args, method);
            }
        }

        class Instance implements StoredMethod {
            private final MethodHandle method;

            private Instance(int args, MethodHandle method) {
                this.method = method.asSpreader(Object[].class, args).asType(METHOD_TYPE);
            }

            @Override
            public Object invk(Object instance, Object[] args) {
                try {
                    return method.invokeExact(instance, args);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }
        }

        class Static implements StoredMethod {
            private final MethodHandle method;

            private Static(int args, MethodHandle method) {
                this.method = method.asSpreader(Object[].class, args).asType(STATIC_METHOD_TYPE);
            }

            @Override
            public Object invk(Object instance, Object[] args) {
                try {
                    return method.invokeExact(args);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public interface StoredField {
        Object get(Object instance);

        void set(Object instance, Object value);

        static StoredField of(boolean isInstance, VarHandle handle) {
            if (isInstance) {
                return new Instance(handle);
            } else {
                return new Static(handle);
            }
        }

        class Instance implements StoredField {
            private final VarHandle varHandle;

            private Instance(VarHandle handle) {
                this.varHandle = handle;
            }

            @Override
            public Object get(Object instance) {
                return varHandle.get(instance);
            }

            @Override
            public void set(Object instance, Object value) {
                varHandle.set(instance, value);
            }
        }

        class Static implements StoredField {
            private final VarHandle varHandle;

            private Static(VarHandle handle) {
                this.varHandle = handle;
            }

            @Override
            public Object get(Object instance) {
                return varHandle.get();
            }

            @Override
            public void set(Object instance, Object value) {
                varHandle.set(value);
            }
        }
    }

    public static class RClass {
        private final Supplier<Class<?>> lazyClz;

        private Class<?> clz = null;

        private RClass(String names) {
            lazyClz = () -> {
                String[] classNames = names.split("/");
                return cache(
                    clazzCache, null, new Class<?>[0], classNames, () -> {
                        for (String clazz : classNames) {
                            try {
                                return Class.forName(clazz);
                            } catch (ClassNotFoundException ignored) {
                            }
                        }
                        throw new RuntimeException("Class not found from " + Arrays.toString(classNames));
                    }
                );
            };
        }

        private RClass(Class<?> clz) {
            lazyClz = () -> clz;
        }

        public RInstance inst(Object inst) {
            try {
                return new RInstance(self(), self().cast(inst));
            } catch (ClassCastException e) {
                throw new RuntimeException("Object is not of type " + self().getName() + ", instead: " + inst.getClass().getName());
            }
        }

        public RConstructor constr(Class<?>... types) {
            return new RConstructor(self(), types);
        }

        public RField fld(String names, Class<?> type) {
            return new RField(null, names, self(), type);
        }

        public RMethod mthd(String names, Class<?>... types) {
            return new RMethod(null, names, self(), types);
        }

        public Class<?> self() {
            if (clz == null) {
                clz = lazyClz.get();
            }
            return clz;
        }

        public RClass arrayType() {
            return clz(self().arrayType());
        }
    }

    public static class RInstance {
        private final Class<?> clz;

        private final Object inst;

        private RInstance(Class<?> clz, Object inst) {
            this.clz = clz;
            this.inst = inst;
        }

        public RField fld(String names, Class<?> type) {
            return new RField(inst, names, clz, type);
        }

        public RMethod mthd(String names, Class<?>... types) {
            return new RMethod(inst, names, clz, types);
        }

        public Object self() {
            return inst;
        }
    }

    public static class RMethod {
        private final Object inst;

        private final Supplier<StoredMethod> lazyMethod;

        private StoredMethod method = null;

        public RMethod(Object inst, String names, Class<?> clz, Class<?>[] types) {
            this.inst = inst;
            String[] methodNames = names.split("/");
            this.lazyMethod = () -> cache(
                methodsCache, clz, types, methodNames, () -> {
                    Set<String> nameSet = Set.of(methodNames);
                    for (Method method : clz.getDeclaredMethods()) {
                        if (nameSet.contains(method.getName()) && methodMatches(method, types)) {
                            try {
                                method.setAccessible(true);
                                MethodHandle handle = LOOKUP.unreflect(method);
                                if (method.isVarArgs()) {
                                    handle = handle.asFixedArity();
                                }
                                return StoredMethod.of(method.getParameterCount(), inst != null, handle);
                            } catch (IllegalAccessException ignored) {
                            }
                        }
                    }
                    throw new RuntimeException("Method not found from " + Arrays.toString(methodNames) + " for " + clz + " with params " + Arrays.toString(types));
                }
            );
        }

        public Object invk(Object... args) {
            return self().invk(inst, args);
        }

        public StoredMethod self() {
            if (method == null) {
                method = lazyMethod.get();
            }
            return method;
        }
    }

    public static class RField {
        private final Object inst;

        private final Supplier<StoredField> lazyField;

        private StoredField fld = null;

        public RField(Object inst, String names, Class<?> clz, Class<?> fieldType) {
            this.inst = inst;
            String[] fieldNames = names.split("/");
            this.lazyField = () -> cache(
                fieldsCache, clz, new Class<?>[]{ fieldType }, fieldNames, () -> {
                    Set<String> nameSet = Set.of(fieldNames);
                    for (Field field : clz.getDeclaredFields()) {
                        if (nameSet.contains(field.getName()) && fieldMatches(field, fieldType)) {
                            try {
                                field.setAccessible(true);
                                return StoredField.of(inst != null, LOOKUP.unreflectVarHandle(field));
                            } catch (IllegalAccessException ignored) {
                            }
                        }
                    }
                    throw new RuntimeException("Field not found from " + Arrays.toString(fieldNames) + " for " + clz + " of type " + fieldType);
                }
            );
        }

        public void set(Object value) {
            self().set(inst, value);
        }

        public Object get() {
            return self().get(inst);
        }

        public StoredField self() {
            if (fld == null) {
                fld = lazyField.get();
            }
            return fld;
        }
    }

    public static class RConstructor {
        private final Class<?> clz;

        private final Supplier<MethodHandle> lazyConstr;

        private MethodHandle constr = null;

        private RConstructor(Class<?> clz, Class<?>... types) {
            this.clz = clz;
            this.lazyConstr = () -> cache(
                constructorsCache, clz, types, new String[0], () -> {
                    try {
                        Constructor<?> c = clz.getDeclaredConstructor(types);
                        c.setAccessible(true);
                        MethodHandle handle = LOOKUP.unreflectConstructor(c);
                        if (c.isVarArgs()) {
                            handle = handle.asFixedArity();
                        }
                        return handle.asSpreader(Object[].class, c.getParameterCount()).asType(CONSTRUCTOR_TYPE);
                    } catch (NoSuchMethodException | IllegalAccessException e) {
                        throw new RuntimeException("Constructor not found for " + clz + " with args " + Arrays.toString(types));
                    }
                }
            );
        }

        public RInstance newInst(Object... args) {
            try {
                return new RInstance(clz, self().invokeExact(args));
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        public MethodHandle self() {
            if (constr == null) {
                constr = lazyConstr.get();
            }
            return constr;
        }
    }

}
