package net.bytebuddy.pool;

import net.bytebuddy.instrumentation.attribute.annotation.AnnotationDescription;
import net.bytebuddy.instrumentation.attribute.annotation.AnnotationList;
import net.bytebuddy.instrumentation.field.FieldDescription;
import net.bytebuddy.instrumentation.field.FieldList;
import net.bytebuddy.instrumentation.method.MethodDescription;
import net.bytebuddy.instrumentation.method.MethodList;
import net.bytebuddy.instrumentation.type.TypeDescription;
import net.bytebuddy.instrumentation.type.TypeList;
import net.bytebuddy.utility.PropertyDispatcher;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static net.bytebuddy.instrumentation.method.matcher.MethodMatchers.*;

/**
 * A type pool allows the retreival of {@link net.bytebuddy.instrumentation.type.TypeDescription} by its name.
 */
public interface TypePool {

    /**
     * Locates and describes the given type by its name. If no such type can be found, an
     * {@link java.lang.IllegalArgumentException} is thrown.
     *
     * @param name The name of the type to describe. The name is to be written as when calling {@link Object#toString()}
     *             on a loaded {@link java.lang.Class}.
     * @return A description of the given type.
     */
    TypeDescription describe(String name);

    /**
     * Clears this type pool's cache.
     */
    void clear();

    /**
     * A cache provider for a {@link net.bytebuddy.pool.TypePool}.
     */
    static interface CacheProvider {

        /**
         * Attempts to find a type in this cache.
         *
         * @param name The name of the type to describe.
         * @return A description of the type or {@code null} if no such type can be found in the cache..
         */
        TypeDescription find(String name);

        /**
         * Registers a type in this cache. If a type of this name already exists in the cache, it should be discarded.
         *
         * @param typeDescription The type to register.
         * @return The oldest version of this type description that is currently registered in the cache.
         */
        TypeDescription register(TypeDescription typeDescription);

        /**
         * Clears this cache.
         */
        void clear();

        /**
         * A non-operational cache that does not store any type descriptions.
         */
        static enum NoOp implements CacheProvider {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public TypeDescription find(String name) {
                return null;
            }

            @Override
            public TypeDescription register(TypeDescription typeDescription) {
                return typeDescription;
            }

            @Override
            public void clear() {
                /* do nothing */
            }
        }

        /**
         * A simple, thread-safe type cache based on a {@link java.util.concurrent.ConcurrentHashMap}.
         */
        static class Simple implements CacheProvider {

            /**
             * A map containing all cached values.
             */
            private final ConcurrentMap<String, TypeDescription> cache;

            /**
             * Creates a new simple cache.
             */
            public Simple() {
                cache = new ConcurrentHashMap<String, TypeDescription>();
            }

            @Override
            public TypeDescription find(String name) {
                return cache.get(name);
            }

            @Override
            public TypeDescription register(TypeDescription typeDescription) {
                TypeDescription cached = cache.putIfAbsent(typeDescription.getName(), typeDescription);
                return cached == null ? typeDescription : cached;
            }

            @Override
            public void clear() {
                cache.clear();
            }

            @Override
            public String toString() {
                return "TypePool.CacheProvider.Simple{cache=" + cache + '}';
            }
        }
    }

    /**
     * A base implementation of a {@link net.bytebuddy.pool.TypePool} that is managing a cache provider and
     * that handles the description of array and primitive types.
     */
    abstract static class AbstractBase implements TypePool {

        /**
         * A map of primitive types by their name.
         */
        protected static final Map<String, TypeDescription> PRIMITIVE_TYPES;

        /**
         * A map of primitive types by their descriptor.
         */
        protected static final Map<String, String> PRIMITIVE_DESCRIPTORS;

        /**
         * The array symbol as used by Java descriptors.
         */
        private static final String ARRAY_SYMBOL = "[";

        /**
         * Initializes the maps of primitive type names and descriptors.
         */
        static {
            Map<String, TypeDescription> primitiveTypes = new HashMap<String, TypeDescription>();
            Map<String, String> primitiveDescriptors = new HashMap<String, String>();
            for (Class<?> primitiveType : new Class<?>[]{boolean.class,
                    byte.class,
                    short.class,
                    char.class,
                    int.class,
                    long.class,
                    float.class,
                    double.class,
                    void.class}) {
                primitiveTypes.put(primitiveType.getName(), new TypeDescription.ForLoadedType(primitiveType));
                primitiveDescriptors.put(Type.getDescriptor(primitiveType), primitiveType.getName());
            }
            PRIMITIVE_TYPES = Collections.unmodifiableMap(primitiveTypes);
            PRIMITIVE_DESCRIPTORS = Collections.unmodifiableMap(primitiveDescriptors);
        }

        /**
         * The cache provider of this instance.
         */
        protected final CacheProvider cacheProvider;

        /**
         * Creates a new instance.
         *
         * @param cacheProvider The cache provider to be used.
         */
        protected AbstractBase(CacheProvider cacheProvider) {
            this.cacheProvider = cacheProvider;
        }

        @Override
        public TypeDescription describe(String name) {
            if (name.contains("/")) {
                throw new IllegalArgumentException(name + " contains the illegal character '/'");
            }
            int arity = 0;
            while (name.startsWith(ARRAY_SYMBOL)) {
                arity++;
                name = name.substring(1);
            }
            if (arity > 0) {
                String primitiveName = PRIMITIVE_DESCRIPTORS.get(name);
                name = primitiveName == null ? name.substring(1, name.length() - 1) : primitiveName;
            }
            TypeDescription typeDescription = PRIMITIVE_TYPES.get(name);
            typeDescription = typeDescription == null ? cacheProvider.find(name) : typeDescription;
            return TypeDescription.ArrayProjection.of(typeDescription == null
                    ? cacheProvider.register(doDescribe(name))
                    : typeDescription, arity);
        }

        @Override
        public void clear() {
            cacheProvider.clear();
        }

        /**
         * Looks up a description of a non-primitive, non-array type. If no such type can be found, an
         * {@link java.lang.IllegalArgumentException} is thrown.
         *
         * @param name The name of the type to describe.
         * @return A description of the type.
         */
        protected abstract TypeDescription doDescribe(String name);

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && cacheProvider.equals(((AbstractBase) other).cacheProvider);
        }

        @Override
        public int hashCode() {
            return cacheProvider.hashCode();
        }
    }

    /**
     * A default implementation of a {@link net.bytebuddy.pool.TypePool} that models binary data in the
     * Java byte code format into a {@link net.bytebuddy.instrumentation.type.TypeDescription}. The data lookup
     * is delegated to a {@link TypeSourceLocator}.
     */
    static class Default extends AbstractBase {

        /**
         * The ASM version that is applied when reading class files.
         */
        private static final int ASM_VERSION = Opcodes.ASM5;

        /**
         * A flag to indicate ASM that no automatic calculations are requested.
         */
        private static final int ASM_MANUAL = 0;

        /**
         * The locator to query for finding binary data of a type.
         */
        private final TypeSourceLocator typeSourceLocator;

        /**
         * Creates a new default type pool.
         *
         * @param cacheProvider     The cache provider to be used.
         * @param typeSourceLocator The type source locator to be used.
         */
        public Default(CacheProvider cacheProvider, TypeSourceLocator typeSourceLocator) {
            super(cacheProvider);
            this.typeSourceLocator = typeSourceLocator;
        }

        /**
         * Creates a default {@link net.bytebuddy.pool.TypePool} that looks up data by querying the system class
         * loader.
         *
         * @return A type pool that reads its data from the system class path.
         */
        public static TypePool ofClassPath() {
            return new Default(new CacheProvider.Simple(), TypeSourceLocator.ForClassLoader.ofSystemClassLoader());
        }

        @Override
        protected TypeDescription doDescribe(String name) {
            byte[] binaryRepresentation = typeSourceLocator.locate(name);
            if (binaryRepresentation == null) {
                throw new IllegalArgumentException("Cannot locate " + name + " using " + typeSourceLocator);
            }
            return parse(binaryRepresentation);
        }

        /**
         * Parses a binary representation and transforms it into a type description.
         *
         * @param binaryRepresentation The binary data to be parsed.
         * @return A type description of the binary data.
         */
        private TypeDescription parse(byte[] binaryRepresentation) {
            ClassReader classReader = new ClassReader(binaryRepresentation);
            TypeExtractor typeExtractor = new TypeExtractor();
            classReader.accept(typeExtractor, ASM_MANUAL);
            return typeExtractor.toTypeDescription();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && super.equals(other)
                    && typeSourceLocator.equals(((Default) other).typeSourceLocator);
        }

        @Override
        public int hashCode() {
            return 31 * super.hashCode() + typeSourceLocator.hashCode();
        }

        @Override
        public String toString() {
            return "TypePool.Default{" +
                    "typeSourceLocator=" + typeSourceLocator +
                    ", cacheProvider=" + cacheProvider +
                    '}';
        }

        /**
         * An annotation registrant implements a visitor pattern for reading an unknown amount of values of annotations.
         */
        protected static interface AnnotationRegistrant {

            /**
             * Registers an annotation value.
             *
             * @param name            The name of the annotation value.
             * @param annotationValue The value of the annotation.
             */
            void register(String name, LazyTypeDescription.AnnotationValue<?, ?> annotationValue);

            /**
             * Called once all annotation values are visited.
             */
            void onComplete();
        }

        /**
         * A component type locator allows for the lazy
         */
        protected static interface ComponentTypeLocator {

            LazyTypeDescription.AnnotationValue.ForComplexArray.ComponentTypeReference bind(String name);

            static enum Illegal implements ComponentTypeLocator {

                INSTANCE;

                @Override
                public LazyTypeDescription.AnnotationValue.ForComplexArray.ComponentTypeReference bind(String name) {
                    throw new IllegalStateException("Unexpected lookup of component type for " + name);
                }
            }

            static class ForAnnotationProperty implements ComponentTypeLocator {

                private final TypePool typePool;

                private final String annotationName;

                public ForAnnotationProperty(TypePool typePool, String annotationDescriptor) {
                    this.typePool = typePool;
                    annotationName = annotationDescriptor.substring(1, annotationDescriptor.length() - 1).replace('/', '.');
                }

                @Override
                public LazyTypeDescription.AnnotationValue.ForComplexArray.ComponentTypeReference bind(String name) {
                    return new Bound(name);
                }

                private class Bound implements LazyTypeDescription.AnnotationValue.ForComplexArray.ComponentTypeReference {

                    private final String name;

                    private Bound(String name) {
                        this.name = name;
                    }

                    @Override
                    public String lookup() {
                        return typePool.describe(annotationName)
                                .getDeclaredMethods()
                                .filter(named(name))
                                .getOnly()
                                .getReturnType()
                                .getComponentType()
                                .getName();
                    }
                }
            }

            static class FixedArrayReturnType implements ComponentTypeLocator, LazyTypeDescription.AnnotationValue.ForComplexArray.ComponentTypeReference {

                private final String componentType;

                public FixedArrayReturnType(String methodDescriptor) {
                    String arrayType = Type.getMethodType(methodDescriptor).getReturnType().getClassName();
                    componentType = arrayType.substring(0, arrayType.length() - 2);
                }

                @Override
                public LazyTypeDescription.AnnotationValue.ForComplexArray.ComponentTypeReference bind(String name) {
                    return this;
                }

                @Override
                public String lookup() {
                    return componentType;
                }
            }
        }

        /**
         * A type extractor reads a class file and collects data that is relevant to create a type description.
         */
        protected class TypeExtractor extends ClassVisitor {

            /**
             * A list of annotation tokens describing annotations that are found on the visited type.
             */
            private final List<LazyTypeDescription.AnnotationToken> annotationTokens;

            /**
             * A list of field tokens describing fields that are found on the visited type.
             */
            private final List<LazyTypeDescription.FieldToken> fieldTokens;

            /**
             * A list of method tokens describing annotations that are found on the visited type.
             */
            private final List<LazyTypeDescription.MethodToken> methodTokens;

            /**
             * The modifiers found for this type.
             */
            private int modifiers;

            /**
             * The internal name found for this type.
             */
            private String internalName;

            /**
             * The internal name of the super type found for this type or {@code null} if no such type exists.
             */
            private String superTypeName;

            /**
             * A list of internal names of interfaces implemented by this type or {@code null} if no interfaces
             * are implemented.
             */
            private String[] interfaceName;

            /**
             * {@code true} if this type was found to represent an anonymous type.
             */
            private boolean anonymousType;

            /**
             * The declaration context found for this type.
             */
            private LazyTypeDescription.DeclarationContext declarationContext;

            /**
             * Creates a new type extractor.
             */
            protected TypeExtractor() {
                super(ASM_VERSION);
                annotationTokens = new LinkedList<LazyTypeDescription.AnnotationToken>();
                fieldTokens = new LinkedList<LazyTypeDescription.FieldToken>();
                methodTokens = new LinkedList<LazyTypeDescription.MethodToken>();
                anonymousType = false;
                declarationContext = LazyTypeDescription.DeclarationContext.SelfDeclared.INSTANCE;
            }

            @Override
            public void visit(int classFileVersion,
                              int modifiers,
                              String internalName,
                              String genericSignature,
                              String superTypeName,
                              String[] interfaceName) {
                this.modifiers = modifiers;
                this.internalName = internalName;
                this.superTypeName = superTypeName;
                this.interfaceName = interfaceName;
            }

            @Override
            public void visitOuterClass(String typeName, String methodName, String methodDescriptor) {
                if (methodName != null) {
                    declarationContext = new LazyTypeDescription.DeclarationContext.DeclaredInMethod(typeName,
                            methodName,
                            methodDescriptor);
                } else if (typeName != null) {
                    declarationContext = new LazyTypeDescription.DeclarationContext.DeclaredInType(typeName);
                }
            }

            @Override
            public void visitInnerClass(String internalName, String outerName, String innerName, int modifiers) {
                if (internalName.equals(this.internalName)) {
                    this.modifiers = modifiers;
                    if (innerName == null) {
                        anonymousType = true;
                    }
                    if (declarationContext.isSelfDeclared()) {
                        declarationContext = new LazyTypeDescription.DeclarationContext.DeclaredInType(outerName);
                    }
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return new AnnotationExtractor(new OnTypeCollector(descriptor),
                        new ComponentTypeLocator.ForAnnotationProperty(Default.this, descriptor));
            }

            @Override
            public FieldVisitor visitField(int modifiers,
                                           String internalName,
                                           String descriptor,
                                           String genericSignature,
                                           Object defaultValue) {
                return new FieldExtractor(modifiers, internalName, descriptor);
            }

            @Override
            public MethodVisitor visitMethod(int modifiers,
                                             String internalName,
                                             String descriptor,
                                             String genericSignature,
                                             String[] exceptionName) {
                if (internalName.equals(MethodDescription.TYPE_INITIALIZER_INTERNAL_NAME)) {
                    return null;
                }
                return new MethodExtractor(modifiers, internalName, descriptor, exceptionName);
            }

            /**
             * Creates a type description from all data that is currently collected. This method should only be invoked
             * after a class file was parsed fully.
             *
             * @return A type description reflecting the data that was collected by this instance.
             */
            protected TypeDescription toTypeDescription() {
                return new LazyTypeDescription(Default.this,
                        modifiers,
                        internalName,
                        superTypeName,
                        interfaceName,
                        declarationContext,
                        anonymousType,
                        annotationTokens,
                        fieldTokens,
                        methodTokens);
            }

            @Override
            public String toString() {
                return "TypePool.Default.TypeExtractor{" +
                        "annotationTokens=" + annotationTokens +
                        ", fieldTokens=" + fieldTokens +
                        ", methodTokens=" + methodTokens +
                        ", modifiers=" + modifiers +
                        ", internalName='" + internalName + '\'' +
                        ", superTypeName='" + superTypeName + '\'' +
                        ", interfaceName=" + Arrays.toString(interfaceName) +
                        ", anonymousType=" + anonymousType +
                        ", declarationContext=" + declarationContext +
                        '}';
            }

            protected class OnTypeCollector implements AnnotationRegistrant {

                private final String descriptor;

                private final Map<String, LazyTypeDescription.AnnotationValue<?, ?>> values;

                protected OnTypeCollector(String descriptor) {
                    this.descriptor = descriptor;
                    values = new HashMap<String, LazyTypeDescription.AnnotationValue<?, ?>>();
                }

                @Override
                public void register(String name, LazyTypeDescription.AnnotationValue<?, ?> annotationValue) {
                    values.put(name, annotationValue);
                }

                @Override
                public void onComplete() {
                    annotationTokens.add(new LazyTypeDescription.AnnotationToken(descriptor, values));
                }
            }

            protected class AnnotationExtractor extends AnnotationVisitor {

                private final AnnotationRegistrant annotationRegistrant;

                private final ComponentTypeLocator componentTypeLocator;

                protected AnnotationExtractor(AnnotationRegistrant annotationRegistrant,
                                              ComponentTypeLocator componentTypeLocator) {
                    super(ASM_VERSION);
                    this.annotationRegistrant = annotationRegistrant;
                    this.componentTypeLocator = componentTypeLocator;
                }

                @Override
                public void visit(String name, Object value) {
                    LazyTypeDescription.AnnotationValue<?, ?> annotationValue;
                    if (value instanceof Type) {
                        annotationValue = new LazyTypeDescription.AnnotationValue.ForType((Type) value);
                    } else if (value.getClass().isArray()) {
                        annotationValue = new LazyTypeDescription.AnnotationValue.Trivial<Object>(value);
                    } else {
                        annotationValue = new LazyTypeDescription.AnnotationValue.Trivial<Object>(value);
                    }
                    annotationRegistrant.register(name, annotationValue);
                }

                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    annotationRegistrant.register(name, new LazyTypeDescription.AnnotationValue.ForEnumeration(descriptor, value));
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    return new AnnotationExtractor(new AnnotationLookup(name, descriptor),
                            new ComponentTypeLocator.ForAnnotationProperty(TypePool.Default.this, descriptor));
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return new AnnotationExtractor(new ArrayLookup(name, componentTypeLocator.bind(name)),
                            ComponentTypeLocator.Illegal.INSTANCE);
                }

                @Override
                public void visitEnd() {
                    annotationRegistrant.onComplete();
                }

                protected class ArrayLookup implements AnnotationRegistrant {

                    private final String name;

                    private final LazyTypeDescription.AnnotationValue.ForComplexArray.ComponentTypeReference componentTypeReference;

                    private final LinkedList<LazyTypeDescription.AnnotationValue<?, ?>> values;

                    protected ArrayLookup(String name,
                                          LazyTypeDescription.AnnotationValue.ForComplexArray.ComponentTypeReference componentTypeReference) {
                        this.name = name;
                        this.componentTypeReference = componentTypeReference;
                        values = new LinkedList<LazyTypeDescription.AnnotationValue<?, ?>>();
                    }

                    @Override
                    public void register(String ignored, LazyTypeDescription.AnnotationValue<?, ?> annotationValue) {
                        values.add(annotationValue);
                    }

                    @Override
                    public void onComplete() {
                        annotationRegistrant.register(name, new LazyTypeDescription.AnnotationValue.ForComplexArray(componentTypeReference, values));
                    }
                }

                private class AnnotationLookup implements AnnotationRegistrant {

                    private final String name;

                    private final String descriptor;

                    private final Map<String, LazyTypeDescription.AnnotationValue<?, ?>> values;

                    protected AnnotationLookup(String name, String descriptor) {
                        this.name = name;
                        this.descriptor = descriptor;
                        values = new HashMap<String, LazyTypeDescription.AnnotationValue<?, ?>>();
                    }

                    @Override
                    public void register(String name, LazyTypeDescription.AnnotationValue<?, ?> annotationValue) {
                        values.put(name, annotationValue);
                    }

                    @Override
                    public void onComplete() {
                        annotationRegistrant.register(name, new LazyTypeDescription.AnnotationValue
                                .ForAnnotation(new LazyTypeDescription.AnnotationToken(descriptor, values)));
                    }
                }
            }

            protected class FieldExtractor extends FieldVisitor {

                private final int modifiers;

                private final String internalName;

                private final String descriptor;

                private final List<LazyTypeDescription.AnnotationToken> annotationTokens;

                protected FieldExtractor(int modifiers, String internalName, String descriptor) {
                    super(ASM_VERSION);
                    this.modifiers = modifiers;
                    this.internalName = internalName;
                    this.descriptor = descriptor;
                    annotationTokens = new LinkedList<LazyTypeDescription.AnnotationToken>();
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return new AnnotationExtractor(new OnFieldCollector(descriptor),
                            new ComponentTypeLocator.ForAnnotationProperty(Default.this, descriptor));
                }

                @Override
                public void visitEnd() {
                    fieldTokens.add(new LazyTypeDescription.FieldToken(modifiers, internalName, descriptor, annotationTokens));
                }

                protected class OnFieldCollector implements AnnotationRegistrant {

                    private final String descriptor;

                    private final Map<String, LazyTypeDescription.AnnotationValue<?, ?>> values;

                    protected OnFieldCollector(String descriptor) {
                        this.descriptor = descriptor;
                        values = new HashMap<String, LazyTypeDescription.AnnotationValue<?, ?>>();
                    }

                    @Override
                    public void register(String name, LazyTypeDescription.AnnotationValue<?, ?> annotationValue) {
                        values.put(name, annotationValue);
                    }

                    @Override
                    public void onComplete() {
                        annotationTokens.add(new LazyTypeDescription.AnnotationToken(descriptor, values));
                    }
                }
            }

            /**
             * A visitor for a method that extracts
             */
            protected class MethodExtractor extends MethodVisitor implements AnnotationRegistrant {

                private final int modifiers;

                private final String internalName;

                private final String descriptor;

                private final String[] exceptionName;

                private final List<LazyTypeDescription.AnnotationToken> annotationTokens;

                private final Map<Integer, List<LazyTypeDescription.AnnotationToken>> parameterAnnotationTokens;

                private LazyTypeDescription.AnnotationValue<?, ?> defaultValue;

                protected MethodExtractor(int modifiers,
                                          String internalName,
                                          String descriptor,
                                          String[] exceptionName) {
                    super(ASM_VERSION);
                    this.modifiers = modifiers;
                    this.internalName = internalName;
                    this.descriptor = descriptor;
                    this.exceptionName = exceptionName;
                    annotationTokens = new LinkedList<LazyTypeDescription.AnnotationToken>();
                    parameterAnnotationTokens = new HashMap<Integer, List<LazyTypeDescription.AnnotationToken>>();
                    for (int i = 0; i < Type.getMethodType(descriptor).getArgumentTypes().length; i++) {
                        parameterAnnotationTokens.put(i, new LinkedList<LazyTypeDescription.AnnotationToken>());
                    }
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return new AnnotationExtractor(new OnMethodCollector(descriptor),
                            new ComponentTypeLocator.ForAnnotationProperty(Default.this, descriptor));
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int index, String descriptor, boolean visible) {
                    return new AnnotationExtractor(new OnMethodParameterCollector(descriptor, index),
                            new ComponentTypeLocator.ForAnnotationProperty(Default.this, descriptor));
                }

                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return new AnnotationExtractor(this, new ComponentTypeLocator.FixedArrayReturnType(descriptor));
                }

                @Override
                public void register(String ignored, LazyTypeDescription.AnnotationValue<?, ?> annotationValue) {
                    defaultValue = annotationValue;
                }

                @Override
                public void onComplete() {
                    /* do nothing, as the register method is called at most once for default values */
                }

                @Override
                public void visitEnd() {
                    methodTokens.add(new LazyTypeDescription.MethodToken(modifiers,
                            internalName,
                            descriptor,
                            exceptionName,
                            annotationTokens,
                            parameterAnnotationTokens,
                            defaultValue));
                }

                protected class OnMethodCollector implements AnnotationRegistrant {

                    private final String descriptor;

                    private final Map<String, LazyTypeDescription.AnnotationValue<?, ?>> values;

                    protected OnMethodCollector(String descriptor) {
                        this.descriptor = descriptor;
                        values = new HashMap<String, LazyTypeDescription.AnnotationValue<?, ?>>();
                    }

                    @Override
                    public void register(String name, LazyTypeDescription.AnnotationValue<?, ?> annotationValue) {
                        values.put(name, annotationValue);
                    }

                    @Override
                    public void onComplete() {
                        annotationTokens.add(new LazyTypeDescription.AnnotationToken(descriptor, values));
                    }
                }

                protected class OnMethodParameterCollector implements AnnotationRegistrant {

                    private final String descriptor;

                    private final int index;

                    private final Map<String, LazyTypeDescription.AnnotationValue<?, ?>> values;

                    protected OnMethodParameterCollector(String descriptor, int index) {
                        this.descriptor = descriptor;
                        this.index = index;
                        values = new HashMap<String, LazyTypeDescription.AnnotationValue<?, ?>>();
                    }

                    @Override
                    public void register(String name, LazyTypeDescription.AnnotationValue<?, ?> annotationValue) {
                        values.put(name, annotationValue);
                    }

                    @Override
                    public void onComplete() {
                        parameterAnnotationTokens.get(index).add(new LazyTypeDescription.AnnotationToken(descriptor, values));
                    }
                }
            }
        }
    }

    /**
     * A type description that looks up any referenced {@link net.bytebuddy.instrumentation.ByteCodeElement}s or
     * {@link net.bytebuddy.instrumentation.attribute.annotation.AnnotationDescription}s by querying a type pool
     * at lookup time.
     */
    static class LazyTypeDescription extends TypeDescription.AbstractTypeDescription.OfSimpleType {

        /**
         * The type pool to be used for looking up linked types.
         */
        private final TypePool typePool;

        /**
         * The modifiers of this type.
         */
        private final int modifiers;

        /**
         * The binary name of this type.
         */
        private final String name;

        /**
         * The binary name of the super type of this type or {@code null} if no such type exists.
         */
        private final String superTypeName;

        /**
         * An array of internal names of all interfaces implemented by this type or {@code null} if no such
         * interfaces exist.
         */
        private final String[] interfaceInternalName;

        /**
         * The declaration context of this type.
         */
        private final DeclarationContext declarationContext;

        /**
         * {@code true} if this type is an anonymous type.
         */
        private final boolean anonymousType;

        /**
         * A list of annotation descriptions that are declared by this type.
         */
        private final List<AnnotationDescription> declaredAnnotations;

        /**
         * A list of field descriptions that are declared by this type.
         */
        private final List<FieldDescription> declaredFields;

        /**
         * A list of method descriptions that are declared by this type.
         */
        private final List<MethodDescription> declaredMethods;

        /**
         * Creates a new lazy type description.
         *
         * @param typePool           The type pool to be used for looking up linked types.
         * @param modifiers          The modifiers of this type.
         * @param name               The internal name of this type.
         * @param superTypeName      The internal name of this type's super type or {@code null} if no such type
         *                           exists.
         * @param interfaceName      An array of the internal names of all implemented interfaces or {@code null} if no
         *                           interfaces are implemented.
         * @param declarationContext The declaration context of this type.
         * @param anonymousType      {@code true} if this type is an anonymous type.
         * @param annotationTokens   A list of annotation tokens representing methods that are declared by this type.
         * @param fieldTokens        A list of field tokens representing methods that are declared by this type.
         * @param methodTokens       A list of method tokens representing methods that are declared by this type.
         */
        protected LazyTypeDescription(TypePool typePool,
                                      int modifiers,
                                      String name,
                                      String superTypeName,
                                      String[] interfaceName,
                                      DeclarationContext declarationContext,
                                      boolean anonymousType,
                                      List<AnnotationToken> annotationTokens,
                                      List<FieldToken> fieldTokens,
                                      List<MethodToken> methodTokens) {
            this.typePool = typePool;
            this.modifiers = modifiers;
            this.name = name.replace('/', '.');
            this.superTypeName = superTypeName == null ? null : superTypeName.replace('/', '.');
            this.interfaceInternalName = interfaceName;
            this.declarationContext = declarationContext;
            this.anonymousType = anonymousType;
            declaredAnnotations = new ArrayList<AnnotationDescription>(annotationTokens.size());
            for (AnnotationToken annotationToken : annotationTokens) {
                declaredAnnotations.add(annotationToken.toAnnotationDescription(typePool));
            }
            declaredFields = new ArrayList<FieldDescription>(fieldTokens.size());
            for (FieldToken fieldToken : fieldTokens) {
                declaredFields.add(fieldToken.toFieldDescription(this));
            }
            declaredMethods = new ArrayList<MethodDescription>(methodTokens.size());
            for (MethodToken methodToken : methodTokens) {
                declaredMethods.add(methodToken.toMethodDescription(this));
            }
        }

        @Override
        public TypeDescription getSupertype() {
            return superTypeName == null || isInterface()
                    ? null
                    : typePool.describe(superTypeName);
        }

        @Override
        public TypeList getInterfaces() {
            return interfaceInternalName == null
                    ? new TypeList.Empty()
                    : new LazyTypeList(interfaceInternalName);
        }

        @Override
        public MethodDescription getEnclosingMethod() {
            return declarationContext.getEnclosingMethod(typePool);
        }

        @Override
        public TypeDescription getEnclosingClass() {
            return declarationContext.getEnclosingType(typePool);
        }

        @Override
        public String getCanonicalName() {
            return name.replace('$', '.');
        }

        @Override
        public boolean isAnonymousClass() {
            return anonymousType;
        }

        @Override
        public boolean isLocalClass() {
            return !anonymousType && declarationContext.isDeclaredInMethod();
        }

        @Override
        public boolean isMemberClass() {
            return declarationContext.isDeclaredInType();
        }

        @Override
        public FieldList getDeclaredFields() {
            return new FieldList.Explicit(declaredFields);
        }

        @Override
        public MethodList getDeclaredMethods() {
            return new MethodList.Explicit(declaredMethods);
        }

        @Override
        public boolean isSealed() {
            return false;
        }

        @Override
        public ClassLoader getClassLoader() {
            return null;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public TypeDescription getDeclaringType() {
            return declarationContext.isDeclaredInType()
                    ? declarationContext.getEnclosingType(typePool)
                    : null;
        }

        @Override
        public int getModifiers() {
            return modifiers;
        }

        @Override
        public AnnotationList getDeclaredAnnotations() {
            return new AnnotationList.Explicit(declaredAnnotations);
        }

        /**
         * A declaration context encapsulates information about whether a type was declared within another type
         * or within a method of another type.
         */
        protected static interface DeclarationContext {

            /**
             * Returns the enclosing method or {@code null} if no such method exists.
             *
             * @param typePool The type pool to be used for looking up linked types.
             * @return A method description describing the linked type or {@code null}.
             */
            MethodDescription getEnclosingMethod(TypePool typePool);

            /**
             * Returns the enclosing type or {@code null} if no such type exists.
             *
             * @param typePool The type pool to be used for looking up linked types.
             * @return A type description describing the linked type or {@code null}.
             */
            TypeDescription getEnclosingType(TypePool typePool);

            /**
             * Returns {@code true} if this instance represents a self declared type.
             *
             * @return {@code true} if this instance represents a self declared type.
             */
            boolean isSelfDeclared();

            /**
             * Returns {@code true} if this instance represents a type that was declared within another type but not
             * within a method.
             *
             * @return {@code true} if this instance represents a type that was declared within another type but not
             * within a method.
             */
            boolean isDeclaredInType();

            /**
             * Returns {@code true} if this instance represents a type that was declared within a method.
             *
             * @return {@code true} if this instance represents a type that was declared within a method.
             */
            boolean isDeclaredInMethod();

            /**
             * Represents a self-declared type that is not defined within another type.
             */
            static enum SelfDeclared implements DeclarationContext {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public MethodDescription getEnclosingMethod(TypePool typePool) {
                    return null;
                }

                @Override
                public TypeDescription getEnclosingType(TypePool typePool) {
                    return null;
                }

                @Override
                public boolean isSelfDeclared() {
                    return true;
                }

                @Override
                public boolean isDeclaredInType() {
                    return false;
                }

                @Override
                public boolean isDeclaredInMethod() {
                    return false;
                }

            }

            /**
             * A declaration context representing a type that is declared within another type but not within
             * a method.
             */
            static class DeclaredInType implements DeclarationContext {

                /**
                 * The binary name of the referenced type.
                 */
                private final String name;

                /**
                 * Creates a new declaration context for a type that is declared within another type.
                 *
                 * @param internalName The internal name of the declaring type.
                 */
                public DeclaredInType(String internalName) {
                    name = internalName.replace('/', '.');
                }

                @Override
                public MethodDescription getEnclosingMethod(TypePool typePool) {
                    return null;
                }

                @Override
                public TypeDescription getEnclosingType(TypePool typePool) {
                    return typePool.describe(name);
                }

                @Override
                public boolean isSelfDeclared() {
                    return false;
                }

                @Override
                public boolean isDeclaredInType() {
                    return true;
                }

                @Override
                public boolean isDeclaredInMethod() {
                    return false;
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && name.equals(((DeclaredInType) other).name);
                }

                @Override
                public int hashCode() {
                    return name.hashCode();
                }

                @Override
                public String toString() {
                    return "TypePool.LazyTypeDescription.DeclarationContext.DeclaredInType{" +
                            "name='" + name + '\'' +
                            '}';
                }
            }

            /**
             * A declaration context representing a type that is declared within a method of another type.
             */
            static class DeclaredInMethod implements DeclarationContext {

                /**
                 * The binary name of the declaring type.
                 */
                private final String name;

                /**
                 * The name of the method that is declaring a type.
                 */
                private final String methodName;

                /**
                 * The descriptor of the method that is declaring a type.
                 */
                private final String methodDescriptor;

                /**
                 * Creates a new declaration context for a method that declares a type.
                 *
                 * @param internalName     The internal name of the declaring type.
                 * @param methodName       The name of the method that is declaring a type.
                 * @param methodDescriptor The descriptor of the method that is declaring a type.
                 */
                public DeclaredInMethod(String internalName, String methodName, String methodDescriptor) {
                    name = internalName.replace('/', '.');
                    this.methodName = methodName;
                    this.methodDescriptor = methodDescriptor;
                }

                @Override
                public MethodDescription getEnclosingMethod(TypePool typePool) {
                    return getEnclosingType(typePool).getDeclaredMethods()
                            .filter(MethodDescription.CONSTRUCTOR_INTERNAL_NAME.equals(methodName)
                                    ? isConstructor() : named(methodName)
                                    .and(hasMethodDescriptor(methodDescriptor))).getOnly();
                }

                @Override
                public TypeDescription getEnclosingType(TypePool typePool) {
                    return typePool.describe(name);
                }

                @Override
                public boolean isSelfDeclared() {
                    return false;
                }

                @Override
                public boolean isDeclaredInType() {
                    return false;
                }

                @Override
                public boolean isDeclaredInMethod() {
                    return true;
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    DeclaredInMethod that = (DeclaredInMethod) other;
                    return methodDescriptor.equals(that.methodDescriptor)
                            && methodName.equals(that.methodName)
                            && name.equals(that.name);
                }

                @Override
                public int hashCode() {
                    int result = name.hashCode();
                    result = 31 * result + methodName.hashCode();
                    result = 31 * result + methodDescriptor.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "TypePool.LazyTypeDescription.DeclarationContext.DeclaredInMethod{" +
                            "name='" + name + '\'' +
                            ", methodName='" + methodName + '\'' +
                            ", methodDescriptor='" + methodDescriptor + '\'' +
                            '}';
                }
            }
        }

        /**
         * A value of a {@link net.bytebuddy.pool.TypePool.LazyTypeDescription.LazyAnnotationDescription}.
         *
         * @param <T> The type of the unloaded value of this annotation.
         * @param <S> The type of the loaded value of this annotation.
         */
        protected static interface AnnotationValue<T, S> {

            /**
             * Resolves the unloaded value of this annotation.
             *
             * @param typePool The type pool to be used for looking up linked types.
             * @return The unloaded value of this annotation.
             */
            T resolve(TypePool typePool);

            /**
             * Returns the loaded value of this annotation.
             *
             * @param classLoader The class loader for loading this value.
             * @return The loaded value of this annotation.
             * @throws ClassNotFoundException If a type that represents a loaded value cannot be found.
             */
            S load(ClassLoader classLoader) throws ClassNotFoundException;

            /**
             * Represents a primitive value, a {@link java.lang.String} or an array of the latter types.
             *
             * @param <U> The type where primitive values are represented by their boxed type.
             */
            static class Trivial<U> implements AnnotationValue<U, U> {

                /**
                 * The represented value.
                 */
                private final U value;

                /**
                 * A property dispatcher for the given value.
                 */
                private final PropertyDispatcher propertyDispatcher;

                /**
                 * Creates a new representation of a trivial annotation value.
                 *
                 * @param value The value to represent.
                 */
                public Trivial(U value) {
                    this.value = value;
                    propertyDispatcher = PropertyDispatcher.of(value.getClass());
                }

                @Override
                public U resolve(TypePool typePool) {
                    return value;
                }

                @Override
                public U load(ClassLoader classLoader) throws ClassNotFoundException {
                    return value;
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && propertyDispatcher.equals(value, ((Trivial) other).value);
                }

                @Override
                public int hashCode() {
                    return propertyDispatcher.hashCode(value);
                }

                @Override
                public String toString() {
                    return "TypePool.LazyTypeDescription.AnnotationValue.Trivial{" +
                            "value=" + value +
                            "propertyDispatcher=" + propertyDispatcher +
                            '}';
                }
            }

            /**
             * Represents a nested annotation value.
             */
            static class ForAnnotation implements AnnotationValue<AnnotationDescription, Annotation> {

                /**
                 * The annotation token that represents the nested invocation.
                 */
                private final AnnotationToken annotationToken;

                /**
                 * Creates a new annotation value for a nested annotation.
                 *
                 * @param annotationToken The token that represents the annotation.
                 */
                public ForAnnotation(AnnotationToken annotationToken) {
                    this.annotationToken = annotationToken;
                }

                @Override
                public AnnotationDescription resolve(TypePool typePool) {
                    return annotationToken.toAnnotationDescription(typePool);
                }

                @Override
                public Annotation load(ClassLoader classLoader) throws ClassNotFoundException {
                    Class<?> annotationType = classLoader.loadClass(annotationToken.getDescriptor()
                            .substring(1, annotationToken.getDescriptor().length() - 1)
                            .replace('/', '.'));
                    return (Annotation) Proxy.newProxyInstance(classLoader, new Class<?>[]{annotationType},
                            new AnnotationInvocationHandler(classLoader, annotationType, annotationToken.getValues()));
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && annotationToken.equals(((ForAnnotation) other).annotationToken);
                }

                @Override
                public int hashCode() {
                    return annotationToken.hashCode();
                }

                @Override
                public String toString() {
                    return "TypePool.LazyTypeDescription.AnnotationValue.ForAnnotation{" +
                            "annotationToken=" + annotationToken +
                            '}';
                }
            }

            /**
             * Represents an enumeration value of an annotation.
             */
            static class ForEnumeration implements AnnotationValue<AnnotationDescription.EnumerationValue, Enum<?>> {

                /**
                 * The descriptor of the enumeration type.
                 */
                private final String descriptor;

                /**
                 * The name of the enumeration.
                 */
                private final String value;

                /**
                 * Creates a new enumeration value representation.
                 *
                 * @param descriptor The descriptor of the enumeration type.
                 * @param value      The name of the enumeration.
                 */
                public ForEnumeration(String descriptor, String value) {
                    this.descriptor = descriptor;
                    this.value = value;
                }

                @Override
                public AnnotationDescription.EnumerationValue resolve(TypePool typePool) {
                    return new LazyEnumerationValue(typePool);
                }

                @Override
                @SuppressWarnings("unchecked")
                public Enum<?> load(ClassLoader classLoader) throws ClassNotFoundException {
                    return Enum.valueOf((Class) (classLoader.loadClass(descriptor.substring(1, descriptor.length() - 1)
                            .replace('/', '.'))), value);
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && descriptor.equals(((ForEnumeration) other).descriptor)
                            && value.equals(((ForEnumeration) other).value);
                }

                @Override
                public int hashCode() {
                    return 31 * descriptor.hashCode() + value.hashCode();
                }

                @Override
                public String toString() {
                    return "TypePool.LazyTypeDescription.AnnotationValue.ForEnumeration{" +
                            "descriptor='" + descriptor + '\'' +
                            ", value='" + value + '\'' +
                            '}';
                }

                private class LazyEnumerationValue extends AnnotationDescription.EnumerationValue.AbstractEnumerationValue {

                    private final TypePool typePool;

                    protected LazyEnumerationValue(TypePool typePool) {
                        this.typePool = typePool;
                    }

                    @Override
                    public String getValue() {
                        return value;
                    }

                    @Override
                    public TypeDescription getEnumerationType() {
                        return typePool.describe(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
                    }

                    @Override
                    public <T extends Enum<T>> T load(Class<T> type) {
                        return Enum.valueOf(type, value);
                    }
                }
            }

            /**
             * Represents a type value of an annotation.
             */
            static class ForType implements AnnotationValue<TypeDescription, Class<?>> {

                /**
                 * A convenience reference indicating that a loaded type should not be initialized.
                 */
                private static final boolean NO_INITIALIZATION = false;

                /**
                 * The binary name of the type.
                 */
                private final String name;

                /**
                 * Represents a type value of an annotation.
                 *
                 * @param type A type representation of the type that is referenced by the annotation..
                 */
                public ForType(Type type) {
                    name = type.getSort() == Type.ARRAY
                            ? type.getInternalName().replace('/', '.')
                            : type.getClassName();
                }

                @Override
                public TypeDescription resolve(TypePool typePool) {
                    return typePool.describe(name);
                }

                @Override
                public Class<?> load(ClassLoader classLoader) throws ClassNotFoundException {
                    return Class.forName(name, NO_INITIALIZATION, classLoader);
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && name.equals(((ForType) other).name);
                }

                @Override
                public int hashCode() {
                    return name.hashCode();
                }

                @Override
                public String toString() {
                    return "TypePool.LazyTypeDescription.AnnotationValue.ForType{" +
                            "name='" + name + '\'' +
                            '}';
                }
            }

            /**
             * Represents an array that is referenced by an annotation which does not contain primitive values or
             * {@link java.lang.String}
             */
            static class ForComplexArray implements AnnotationValue<Object[], Object[]> {

                /**
                 * A reference to the component type.
                 */
                private final ComponentTypeReference componentTypeReference;

                /**
                 * A list of all values of this array value in their order.
                 */
                private List<AnnotationValue<?, ?>> value;

                /**
                 * Creates a new array value representation of a complex array.
                 *
                 * @param componentTypeReference A lazy reference to the component type of this array.
                 * @param value                  A list of all values of this annotation.
                 */
                public ForComplexArray(ComponentTypeReference componentTypeReference,
                                       List<AnnotationValue<?, ?>> value) {
                    this.value = value;
                    this.componentTypeReference = componentTypeReference;
                }

                @Override
                public Object[] resolve(TypePool typePool) {
                    TypeDescription componentTypeDescription = typePool.describe(componentTypeReference.lookup());
                    Class<?> componentType;
                    if (componentTypeDescription.represents(Class.class)) {
                        componentType = TypeDescription.class;
                    } else if (componentTypeDescription.isAssignableTo(Enum.class)) {
                        componentType = AnnotationDescription.EnumerationValue.class;
                    } else if (componentTypeDescription.isAssignableTo(Annotation.class)) {
                        componentType = AnnotationDescription.class;
                    } else if (componentTypeDescription.represents(String.class)) {
                        componentType = String.class;
                    } else {
                        throw new IllegalStateException("Unexpected complex array component type " + componentTypeDescription);
                    }
                    Object[] array = (Object[]) Array.newInstance(componentType, value.size());
                    int index = 0;
                    for (AnnotationValue<?, ?> annotationValue : value) {
                        Array.set(array, index++, annotationValue.resolve(typePool));
                    }
                    return array;
                }

                @Override
                public Object[] load(ClassLoader classLoader) throws ClassNotFoundException {
                    Object[] array = (Object[]) Array.newInstance(classLoader.loadClass(componentTypeReference.lookup()), value.size());
                    int index = 0;
                    for (AnnotationValue<?, ?> annotationValue : value) {
                        Array.set(array, index++, annotationValue.load(classLoader));
                    }
                    return array;
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && componentTypeReference.equals(((ForComplexArray) other).componentTypeReference)
                            && value.equals(((ForComplexArray) other).value);
                }

                @Override
                public int hashCode() {
                    return 31 * value.hashCode() + componentTypeReference.hashCode();
                }

                @Override
                public String toString() {
                    return "TypePool.LazyTypeDescription.AnnotationValue.ForComplexArray{" +
                            "value=" + value +
                            ", componentTypeReference=" + componentTypeReference +
                            '}';
                }

                /**
                 * A lazy representation of the component type of an array.
                 */
                public static interface ComponentTypeReference {

                    /**
                     * Lazily returns the binary name of the array component type of an annotation value.
                     *
                     * @return The binary name of the component type.
                     */
                    String lookup();
                }
            }
        }

        /**
         * An invocation handler to represent a loaded annotation of a
         * {@link net.bytebuddy.pool.TypePool.LazyTypeDescription.LazyAnnotationDescription}.
         */
        protected static class AnnotationInvocationHandler implements InvocationHandler {

            /**
             * The name of the {@link Object#hashCode()} method.
             */
            private static final String HASH_CODE = "hashCode";

            /**
             * The name of the {@link Object#equals(Object)} method.
             */
            private static final String EQUALS = "equals";

            /**
             * The name of the {@link Object#toString()} method.
             */
            private static final String TO_STRING = "toString";

            /**
             * The class loader to use.
             */
            private final ClassLoader classLoader;

            /**
             * The loaded annotation type.
             */
            private final Class<?> annotationType;

            /**
             * A sorted list of values of this annotation.
             */
            private final LinkedHashMap<Method, AnnotationValue<?, ?>> values;

            /**
             * Creates a new invocation handler.
             *
             * @param classLoader    The class loader for loading this value.
             * @param annotationType The loaded annotation type.
             * @param values         A sorted list of values of this annotation.
             */
            public AnnotationInvocationHandler(ClassLoader classLoader,
                                               Class<?> annotationType,
                                               Map<String, AnnotationValue<?, ?>> values) {
                this.classLoader = classLoader;
                this.annotationType = annotationType;
                Method[] declaredMethod = annotationType.getDeclaredMethods();
                this.values = new LinkedHashMap<Method, AnnotationValue<?, ?>>(declaredMethod.length);
                TypeDescription thisType = new ForLoadedType(getClass());
                for (Method method : declaredMethod) {
                    if (!new MethodDescription.ForLoadedMethod(method).isVisibleTo(thisType)) {
                        method.setAccessible(true);
                    }
                    AnnotationValue<?, ?> annotationValue = values.get(method.getName());
                    this.values.put(method, annotationValue == null ? ResolvedAnnotationValue.of(method) : annotationValue);
                }
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                if (method.getDeclaringClass() != annotationType) {
                    if (method.getName().equals(HASH_CODE)) {
                        return hashCodeRepresentation();
                    } else if (method.getName().equals(EQUALS) && method.getParameterTypes().length == 1) {
                        return equalsRepresentation(arguments[0]);
                    } else if (method.getName().equals(TO_STRING)) {
                        return toStringRepresentation();
                    } else /* method.getName().equals("annotationType") */ {
                        return annotationType;
                    }
                }
                return invoke(method);
            }

            /**
             * Invokes a method and returns its loaded value.
             *
             * @param method The method to invoke.
             * @return The loaded value of this method.
             * @throws ClassNotFoundException If a class cannot be loaded.
             */
            private Object invoke(Method method) throws ClassNotFoundException {
                return values.get(method).load(classLoader);
            }

            /**
             * Returns the string representation of the represented annotation.
             *
             * @return The string representation of the represented annotation.
             * @throws ClassNotFoundException If a class cannot be loaded.
             */
            protected String toStringRepresentation() throws ClassNotFoundException {
                StringBuilder toString = new StringBuilder();
                toString.append('@');
                toString.append(annotationType.getName());
                toString.append('(');
                boolean firstMember = true;
                for (Map.Entry<Method, AnnotationValue<?, ?>> entry : values.entrySet()) {
                    if (firstMember) {
                        firstMember = false;
                    } else {
                        toString.append(", ");
                    }
                    toString.append(entry.getKey().getName());
                    toString.append('=');
                    toString.append(PropertyDispatcher.of(entry.getKey().getReturnType())
                            .toString(entry.getValue().load(classLoader)));
                }
                toString.append(')');
                return toString.toString();
            }

            /**
             * Returns the hash code of the represented annotation.
             *
             * @return The hash code of the represented annotation.
             * @throws ClassNotFoundException If a class cannot be loaded.
             */
            private int hashCodeRepresentation() throws ClassNotFoundException {
                int hashCode = 0;
                for (Map.Entry<Method, AnnotationValue<?, ?>> entry : values.entrySet()) {
                    hashCode += (127 * entry.getKey().getName().hashCode()) ^ PropertyDispatcher.of(entry.getKey().getReturnType())
                            .hashCode(entry.getValue().load(classLoader));
                }
                return hashCode;
            }

            /**
             * Checks if another instance is equal to this instance.
             *
             * @param other The instance to be examined for equality to the represented instance.
             * @return {@code true} if the given instance is equal to the represented instance.
             * @throws InvocationTargetException If a method causes an exception.
             * @throws IllegalAccessException    If a method is accessed illegally.
             * @throws ClassNotFoundException    If a class cannot be found.
             */
            private boolean equalsRepresentation(Object other)
                    throws InvocationTargetException, IllegalAccessException, ClassNotFoundException {
                if (!annotationType.isInstance(other)) {
                    return false;
                } else if (Proxy.isProxyClass(other.getClass())) {
                    InvocationHandler invocationHandler = Proxy.getInvocationHandler(other);
                    if (invocationHandler instanceof AnnotationInvocationHandler) {
                        return invocationHandler.equals(this);
                    }
                }
                for (Method method : annotationType.getDeclaredMethods()) {
                    Object thisValue = invoke(method);
                    if (!PropertyDispatcher.of(thisValue.getClass()).equals(thisValue, method.invoke(other))) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                AnnotationInvocationHandler that = (AnnotationInvocationHandler) other;
                return annotationType.equals(that.annotationType)
                        && classLoader.equals(that.classLoader)
                        && values.equals(that.values);
            }

            @Override
            public int hashCode() {
                int result = annotationType.hashCode();
                result = 31 * result + classLoader.hashCode();
                result = 31 * result + values.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "TypePool.LazyTypeDescription.AnnotationInvocationHandler{" +
                        "annotationType=" + annotationType +
                        ", classLoader=" + classLoader +
                        ", values=" + values +
                        '}';
            }

            /**
             * Represents a value of an annotation as a default value of a loaded method.
             */
            protected static class ResolvedAnnotationValue implements AnnotationValue<Void, Object> {

                /**
                 * The method for loading a value.
                 */
                protected final Method method;

                /**
                 * Creates a new annotation value.
                 *
                 * @param method The method to invoke.
                 */
                private ResolvedAnnotationValue(Method method) {
                    this.method = method;
                }

                /**
                 * Creates a new annotation value.
                 *
                 * @param method The method to invoke.
                 * @return An annotation value of a default method to represent.
                 */
                protected static AnnotationValue<?, ?> of(Method method) {
                    if (method.getDefaultValue() == null) {
                        throw new IllegalStateException("Expected " + method + " to define a default value");
                    }
                    return new ResolvedAnnotationValue(method);
                }

                @Override
                public Object load(ClassLoader classLoader) throws ClassNotFoundException {
                    return method.getDefaultValue();
                }

                @Override
                public final Void resolve(TypePool typePool) {
                    throw new UnsupportedOperationException("Already resolved annotation values do not support resolution");
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && method.equals(((ResolvedAnnotationValue) other).method);
                }

                @Override
                public int hashCode() {
                    return method.hashCode();
                }

                @Override
                public String toString() {
                    return "TypePool.LazyTypeDescription.AnnotationInvocationHandler.ResolvedAnnotationValue{" +
                            "method=" + method +
                            '}';
                }
            }
        }

        /**
         * A token for representing collected data on an annotation.
         */
        protected static class AnnotationToken {

            /**
             * The descriptor of the represented annotation.
             */
            private final String descriptor;

            /**
             * A map of annotation value names to their value representations.
             */
            private final Map<String, AnnotationValue<?, ?>> values;

            /**
             * Creates a new annotation token.
             *
             * @param descriptor The descriptor of the represented annotation.
             * @param values     A map of annotation value names to their value representations.
             */
            protected AnnotationToken(String descriptor, Map<String, AnnotationValue<?, ?>> values) {
                this.descriptor = descriptor;
                this.values = values;
            }

            /**
             * Returns the descriptor of the represented annotation.
             *
             * @return The descriptor of the represented annotation.
             */
            public String getDescriptor() {
                return descriptor;
            }

            /**
             * Returns a map of annotation value names to their value representations.
             *
             * @return A map of annotation value names to their value representations.
             */
            public Map<String, AnnotationValue<?, ?>> getValues() {
                return values;
            }

            /**
             * Transforms this token into an annotation description.
             *
             * @param typePool The type pool to be used for looking up linked types.
             * @return An annotation description that resembles this token.
             */
            private AnnotationDescription toAnnotationDescription(TypePool typePool) {
                return new LazyAnnotationDescription(typePool, descriptor, values);
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                AnnotationToken that = (AnnotationToken) other;
                return descriptor.equals(that.descriptor)
                        && values.equals(that.values);
            }

            @Override
            public int hashCode() {
                int result = descriptor.hashCode();
                result = 31 * result + values.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "TypePool.LazyTypeDescription.AnnotationToken{" +
                        "descriptor='" + descriptor + '\'' +
                        ", values=" + values +
                        '}';
            }
        }

        /**
         * A token for representing collected data on a field.
         */
        protected static class FieldToken {

            /**
             * The modifiers of the represented field.
             */
            private final int modifiers;

            /**
             * The name of the field.
             */
            private final String name;

            /**
             * The descriptor of the field.
             */
            private final String descriptor;

            /**
             * A list of annotation tokens representing the annotations of the represented field.
             */
            private final List<AnnotationToken> annotationTokens;

            /**
             * Creates a new field token.
             *
             * @param modifiers        The modifiers of the represented field.
             * @param name             The name of the field.
             * @param descriptor       The descriptor of the field.
             * @param annotationTokens A list of annotation tokens representing the annotations of the
             *                         represented field.
             */
            protected FieldToken(int modifiers, String name, String descriptor, List<AnnotationToken> annotationTokens) {
                this.modifiers = modifiers;
                this.name = name;
                this.descriptor = descriptor;
                this.annotationTokens = annotationTokens;
            }

            /**
             * Returns the modifiers of the represented field.
             *
             * @return The modifiers of the represented field.
             */
            protected int getModifiers() {
                return modifiers;
            }

            /**
             * Returns the name of the represented field.
             *
             * @return The name of the represented field.
             */
            protected String getName() {
                return name;
            }

            /**
             * Returns the descriptor of the represented field.
             *
             * @return The descriptor of the represented field.
             */
            protected String getDescriptor() {
                return descriptor;
            }

            /**
             * Returns a list of annotation tokens of the represented field.
             *
             * @return A list of annotation tokens of the represented field.
             */
            public List<AnnotationToken> getAnnotationTokens() {
                return annotationTokens;
            }

            /**
             * Transforms this token into a lazy field description.
             *
             * @param lazyTypeDescription The lazy type description to attach this field description to.
             * @return A field description resembling this field token.
             */
            private FieldDescription toFieldDescription(LazyTypeDescription lazyTypeDescription) {
                return lazyTypeDescription.new LazyFieldDescription(getModifiers(),
                        getName(),
                        getDescriptor(),
                        getAnnotationTokens());
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                FieldToken that = (FieldToken) other;
                return modifiers == that.modifiers
                        && annotationTokens.equals(that.annotationTokens)
                        && descriptor.equals(that.descriptor)
                        && name.equals(that.name);
            }

            @Override
            public int hashCode() {
                int result = modifiers;
                result = 31 * result + name.hashCode();
                result = 31 * result + descriptor.hashCode();
                result = 31 * result + annotationTokens.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "TypePool.LazyTypeDescription.FieldToken{" +
                        "modifiers=" + modifiers +
                        ", name='" + name + '\'' +
                        ", descriptor='" + descriptor + '\'' +
                        ", annotationTokens=" + annotationTokens +
                        '}';
            }
        }

        /**
         * A token for representing collected data on a method.
         */
        protected static class MethodToken {

            /**
             * The modifiers of the represented method.
             */
            private final int modifiers;

            /**
             * The internal name of the represented method.
             */
            private final String name;

            /**
             * The descriptor of the represented method.
             */
            private final String descriptor;

            /**
             * An array of internal names of the exceptions of the represented method or {@code null} if there
             * are no such exceptions.
             */
            private final String[] exceptionName;

            /**
             * A list of annotation tokens that are present on the represented method.
             */
            private final List<AnnotationToken> annotationTokens;

            /**
             * A map of parameter indices to tokens that represent their annotations.
             */
            private final Map<Integer, List<AnnotationToken>> parameterAnnotationTokens;

            /**
             * The default value of this method or {@code null} if there is no such value.
             */
            private final AnnotationValue<?, ?> defaultValue;

            /**
             * Creates a new method token.
             *
             * @param modifiers                 The modifiers of the represented method.
             * @param name                      The internal name of the represented method.
             * @param descriptor                The descriptor of the represented method.
             * @param exceptionName             An array of internal names of the exceptions of the represented method
             *                                  or {@code null} if there are no such exceptions.
             * @param annotationTokens          A list of annotation tokens that are present on the represented method.
             * @param parameterAnnotationTokens A map of parameter indices to tokens that represent their annotations.
             * @param defaultValue              The default value of this method or {@code null} if there is no
             *                                  such value.
             */
            protected MethodToken(int modifiers,
                                  String name,
                                  String descriptor,
                                  String[] exceptionName,
                                  List<AnnotationToken> annotationTokens,
                                  Map<Integer, List<AnnotationToken>> parameterAnnotationTokens,
                                  AnnotationValue<?, ?> defaultValue) {
                this.modifiers = modifiers;
                this.name = name;
                this.descriptor = descriptor;
                this.exceptionName = exceptionName;
                this.annotationTokens = annotationTokens;
                this.parameterAnnotationTokens = parameterAnnotationTokens;
                this.defaultValue = defaultValue;
            }

            /**
             * Returns the modifiers of the represented method.
             *
             * @return The modifiers of the represented method.
             */
            protected int getModifiers() {
                return modifiers;
            }

            /**
             * Returns the internal name of the represented method.
             *
             * @return The internal name of the represented method.
             */
            protected String getName() {
                return name;
            }

            /**
             * Returns the descriptor of the represented method.
             *
             * @return The descriptor of the represented method.
             */
            protected String getDescriptor() {
                return descriptor;
            }

            /**
             * Returns the internal names of the exception type declared of the represented method.
             *
             * @return The internal names of the exception type declared of the represented method.
             */
            protected String[] getExceptionName() {
                return exceptionName;
            }

            /**
             * Returns a list of annotation tokens declared by the represented method.
             *
             * @return A list of annotation tokens declared by the represented method.
             */
            public List<AnnotationToken> getAnnotationTokens() {
                return annotationTokens;
            }

            /**
             * Returns a map of parameter type indices to a list of annotation tokens representing these annotations.
             *
             * @return A map of parameter type indices to a list of annotation tokens representing these annotations.
             */
            public Map<Integer, List<AnnotationToken>> getParameterAnnotationTokens() {
                return parameterAnnotationTokens;
            }

            /**
             * Returns the default value of the represented method or {@code null} if no such values exists.
             *
             * @return The default value of the represented method or {@code null} if no such values exists.
             */
            public AnnotationValue<?, ?> getDefaultValue() {
                return defaultValue;
            }

            /**
             * Trnasforms this method token to a method description that is attached to a lazy type description.
             *
             * @param lazyTypeDescription The lazy type description to attach this method description to.
             * @return A method description representing this field token.
             */
            private MethodDescription toMethodDescription(LazyTypeDescription lazyTypeDescription) {
                return lazyTypeDescription.new LazyMethodDescription(getModifiers(),
                        getName(),
                        getDescriptor(),
                        getExceptionName(),
                        getAnnotationTokens(),
                        getParameterAnnotationTokens(),
                        getDefaultValue());
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                MethodToken that = (MethodToken) other;
                return modifiers == that.modifiers
                        && annotationTokens.equals(that.annotationTokens)
                        && defaultValue.equals(that.defaultValue)
                        && descriptor.equals(that.descriptor)
                        && Arrays.equals(exceptionName, that.exceptionName)
                        && name.equals(that.name)
                        && parameterAnnotationTokens.equals(that.parameterAnnotationTokens);
            }

            @Override
            public int hashCode() {
                int result = modifiers;
                result = 31 * result + name.hashCode();
                result = 31 * result + descriptor.hashCode();
                result = 31 * result + Arrays.hashCode(exceptionName);
                result = 31 * result + annotationTokens.hashCode();
                result = 31 * result + parameterAnnotationTokens.hashCode();
                result = 31 * result + defaultValue.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "TypePool.LazyTypeDescription.MethodToken{" +
                        "modifiers=" + modifiers +
                        ", name='" + name + '\'' +
                        ", descriptor='" + descriptor + '\'' +
                        ", exceptionName=" + Arrays.toString(exceptionName) +
                        ", annotationTokens=" + annotationTokens +
                        ", parameterAnnotationTokens=" + parameterAnnotationTokens +
                        ", defaultValue=" + defaultValue +
                        '}';
            }
        }

        /**
         * A lazy description of an annotation that looks up types from a type pool when required.
         */
        private static class LazyAnnotationDescription extends AnnotationDescription.AbstractAnnotationDescription {

            /**
             * The type pool for looking up type references.
             */
            protected final TypePool typePool;

            /**
             * A map of annotation values by their property name.
             */
            protected final Map<String, AnnotationValue<?, ?>> values;

            /**
             * The descriptor of this annotation.
             */
            private final String descriptor;

            /**
             * Creates a new lazy annotation description.
             *
             * @param typePool   The type pool to be used for looking up linked types.
             * @param descriptor The descriptor of the annotation type.
             * @param values     A map of annotation value names to their value representations.
             */
            private LazyAnnotationDescription(TypePool typePool,
                                              String descriptor,
                                              Map<String, AnnotationValue<?, ?>> values) {
                this.typePool = typePool;
                this.descriptor = descriptor;
                this.values = values;
            }

            @Override
            public Object getValue(MethodDescription methodDescription) {
                if (!methodDescription.getDeclaringType().getDescriptor().equals(descriptor)) {
                    throw new IllegalArgumentException(methodDescription + " is not declared by " + getAnnotationType());
                }
                AnnotationValue<?, ?> annotationValue = values.get(methodDescription.getName());
                return annotationValue == null
                        ? getAnnotationType().getDeclaredMethods().filter(is(methodDescription)).getOnly().getDefaultValue()
                        : annotationValue.resolve(typePool);
            }

            @Override
            public TypeDescription getAnnotationType() {
                return typePool.describe(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
            }

            @Override
            public <T extends Annotation> Loadable<T> prepare(Class<T> annotationType) {
                return new Loadable<T>(typePool, descriptor, values, annotationType);
            }

            /**
             * A loadable version of a lazy annotation description.
             *
             * @param <S> The annotation type.
             */
            private static class Loadable<S extends Annotation> extends LazyAnnotationDescription implements AnnotationDescription.Loadable<S> {

                /**
                 * The loaded annotation type.
                 */
                private final Class<S> annotationType;

                /**
                 * Creates a new loadable version of a lazy annotation.
                 *
                 * @param typePool       The type pool to be used for looking up linked types.
                 * @param descriptor     The descriptor of the represented annotation.
                 * @param values         A map of annotation value names to their value representations.
                 * @param annotationType The loaded annotation type.
                 */
                private Loadable(TypePool typePool,
                                 String descriptor,
                                 Map<String, AnnotationValue<?, ?>> values,
                                 Class<S> annotationType) {
                    super(typePool, descriptor, values);
                    if (!Type.getDescriptor(annotationType).equals(descriptor)) {
                        throw new IllegalArgumentException(annotationType + " does not correspond to " + descriptor);
                    }
                    this.annotationType = annotationType;
                }

                @Override
                @SuppressWarnings("unchecked")
                public S load() {
                    return (S) Proxy.newProxyInstance(annotationType.getClassLoader(), new Class<?>[]{annotationType},
                            new AnnotationInvocationHandler(annotationType.getClassLoader(), annotationType, values));
                }
            }
        }

        /**
         * A lazy field description that only resolved type references when required.
         */
        private class LazyFieldDescription extends FieldDescription.AbstractFieldDescription {

            /**
             * The modifiers of the field.
             */
            private final int modifiers;

            /**
             * The name of the field.
             */
            private final String name;

            /**
             * The binary name of the field's type.
             */
            private final String fieldTypeName;

            /**
             * A list of annotation descriptions of this field.
             */
            private final List<AnnotationDescription> declaredAnnotations;

            /**
             * Creaes a lazy field description.
             *
             * @param modifiers        The modifiers of the represented field.
             * @param name             The name of the field.
             * @param descriptor       The descriptor of the field.
             * @param annotationTokens A list of annotation tokens representing annotations that are declared by this field.
             */
            private LazyFieldDescription(int modifiers,
                                         String name,
                                         String descriptor,
                                         List<AnnotationToken> annotationTokens) {
                this.modifiers = modifiers;
                this.name = name;
                Type fieldType = Type.getType(descriptor);
                fieldTypeName = fieldType.getSort() == Type.ARRAY
                        ? fieldType.getInternalName().replace('/', '.')
                        : fieldType.getClassName();
                declaredAnnotations = new ArrayList<AnnotationDescription>(annotationTokens.size());
                for (AnnotationToken annotationToken : annotationTokens) {
                    declaredAnnotations.add(annotationToken.toAnnotationDescription(typePool));
                }
            }

            @Override
            public TypeDescription getFieldType() {
                return typePool.describe(fieldTypeName);
            }

            @Override
            public AnnotationList getDeclaredAnnotations() {
                return new AnnotationList.Explicit(declaredAnnotations);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public TypeDescription getDeclaringType() {
                return LazyTypeDescription.this;
            }

            @Override
            public int getModifiers() {
                return modifiers;
            }
        }

        /**
         * A lazy representation of a method that resolves references to types only on demand.
         */
        private class LazyMethodDescription extends MethodDescription.AbstractMethodDescription {

            /**
             * The modifiers of this method.
             */
            private final int modifiers;

            /**
             * The internal name of this method.
             */
            private final String internalName;

            /**
             * The binary name of the return type of this method.
             */
            private final String returnTypeName;

            /**
             * A list of parameter type of this method.
             */
            private final TypeList parameterTypes;

            /**
             * A list of exception types of this method.
             */
            private final TypeList exceptionTypes;

            /**
             * A list of annotation descriptions that are declared by this method.
             */
            private final List<AnnotationDescription> declaredAnnotations;

            /**
             * A nested list of annotation descriptions that are declard by the parameters of this
             * method in their oder.
             */
            private final List<List<AnnotationDescription>> declaredParameterAnnotations;

            /**
             * The default value of this method or {@code null} if no such value exists.
             */
            private final AnnotationValue<?, ?> defaultValue;

            /**
             * Creates a new lazy method description.
             *
             * @param modifiers                 The modifiers of the represented method.
             * @param internalName              The internal name of this method.
             * @param methodDescriptor          The method descriptor of this method.
             * @param exceptionInternalName     The internal names of the exceptions that are declared by this
             *                                  method or {@code null} if no exceptions are declared by this
             *                                  method.
             * @param annotationTokens          A list of annotation tokens representing annotations that are declared
             *                                  by this method.
             * @param parameterAnnotationTokens A nested list of annotation tokens representing annotations that are
             *                                  declared by the fields of this method.
             * @param defaultValue              The default value of this method or {@code null} if there is no
             *                                  such value.
             */
            private LazyMethodDescription(int modifiers,
                                          String internalName,
                                          String methodDescriptor,
                                          String[] exceptionInternalName,
                                          List<AnnotationToken> annotationTokens,
                                          Map<Integer, List<AnnotationToken>> parameterAnnotationTokens,
                                          AnnotationValue<?, ?> defaultValue) {
                this.modifiers = modifiers;
                this.internalName = internalName;
                Type returnType = Type.getReturnType(methodDescriptor);
                returnTypeName = returnType.getSort() == Type.ARRAY
                        ? returnType.getDescriptor().replace('/', '.')
                        : returnType.getClassName();
                parameterTypes = new LazyTypeList(methodDescriptor);
                exceptionTypes = exceptionInternalName == null
                        ? new TypeList.Empty()
                        : new LazyTypeList(exceptionInternalName);
                declaredAnnotations = new ArrayList<AnnotationDescription>(annotationTokens.size());
                for (AnnotationToken annotationToken : annotationTokens) {
                    declaredAnnotations.add(annotationToken.toAnnotationDescription(typePool));
                }
                declaredParameterAnnotations = new ArrayList<List<AnnotationDescription>>(parameterTypes.size());
                for (int index = 0; index < parameterTypes.size(); index++) {
                    List<AnnotationToken> tokens = parameterAnnotationTokens.get(index);
                    List<AnnotationDescription> annotationDescriptions;
                    annotationDescriptions = new ArrayList<AnnotationDescription>(tokens.size());
                    for (AnnotationToken annotationToken : tokens) {
                        annotationDescriptions.add(annotationToken.toAnnotationDescription(typePool));
                    }
                    declaredParameterAnnotations.add(annotationDescriptions);
                }
                this.defaultValue = defaultValue;
            }

            @Override
            public TypeDescription getReturnType() {
                return typePool.describe(returnTypeName);
            }

            @Override
            public TypeList getParameterTypes() {
                return parameterTypes;
            }

            @Override
            public TypeList getExceptionTypes() {
                return exceptionTypes;
            }

            @Override
            public boolean isConstructor() {
                return internalName.equals(MethodDescription.CONSTRUCTOR_INTERNAL_NAME);
            }

            @Override
            public boolean isTypeInitializer() {
                return false;
            }

            @Override
            public List<AnnotationList> getParameterAnnotations() {
                return AnnotationList.Explicit.asList(declaredParameterAnnotations);
            }

            @Override
            public AnnotationList getDeclaredAnnotations() {
                return new AnnotationList.Explicit(declaredAnnotations);
            }

            @Override
            public boolean represents(Method method) {
                return equals(new ForLoadedMethod(method));
            }

            @Override
            public boolean represents(Constructor<?> constructor) {
                return equals(new ForLoadedConstructor(constructor));
            }

            @Override
            public String getInternalName() {
                return internalName;
            }

            @Override
            public TypeDescription getDeclaringType() {
                return LazyTypeDescription.this;
            }

            @Override
            public int getModifiers() {
                return modifiers;
            }

            @Override
            public Object getDefaultValue() {
                return defaultValue == null
                        ? null
                        : defaultValue.resolve(typePool);
            }
        }

        /**
         * A list that is constructing {@link net.bytebuddy.pool.TypePool.LazyTypeDescription}s.
         */
        protected class LazyTypeList extends AbstractList<TypeDescription> implements TypeList {

            /**
             * A list of binary names of the represented types.
             */
            private final String[] name;

            /**
             * A list of internal names of the represented types.
             */
            private final String[] internalName;

            /**
             * The stack size of all types in this list.
             */
            private final int stackSize;

            /**
             * Creates a new type list for a method's parameter types.
             *
             * @param methodDescriptor The method which arguments should be represented in this type list.
             */
            protected LazyTypeList(String methodDescriptor) {
                Type[] parameterType = Type.getArgumentTypes(methodDescriptor);
                name = new String[parameterType.length];
                internalName = new String[parameterType.length];
                int index = 0, stackSize = 0;
                for (Type aParameterType : parameterType) {
                    name[index] = aParameterType.getSort() == Type.ARRAY
                            ? aParameterType.getInternalName().replace('/', '.')
                            : aParameterType.getClassName();
                    internalName[index] = aParameterType.getSort() == Type.ARRAY
                            ? aParameterType.getInternalName().replace('/', '.')
                            : aParameterType.getClassName();
                    stackSize += aParameterType.getSize();
                    index++;
                }
                this.stackSize = stackSize;
            }

            /**
             * Creates a new type list for a list of internal names.
             *
             * @param internalName The internal names to represent by this type list.
             */
            protected LazyTypeList(String[] internalName) {
                name = new String[internalName.length];
                this.internalName = internalName;
                int index = 0;
                for (String anInternalName : internalName) {
                    name[index++] = anInternalName.replace('/', '.');
                }
                stackSize = index;
            }

            @Override
            public TypeDescription get(int index) {
                return typePool.describe(name[index]);
            }

            @Override
            public int size() {
                return name.length;
            }

            @Override
            public String[] toInternalNames() {
                return internalName.length == 0 ? null : internalName;
            }

            @Override
            public int getStackSize() {
                return stackSize;
            }

            @Override
            public TypeList subList(int fromIndex, int toIndex) {
                if (fromIndex < 0) {
                    throw new IndexOutOfBoundsException("fromIndex = " + fromIndex);
                } else if (toIndex > internalName.length) {
                    throw new IndexOutOfBoundsException("toIndex = " + toIndex);
                } else if (fromIndex > toIndex) {
                    throw new IllegalArgumentException("fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
                }
                return new LazyTypeList(Arrays.asList(internalName)
                        .subList(fromIndex, toIndex)
                        .toArray(new String[toIndex - fromIndex]));
            }
        }
    }
}
