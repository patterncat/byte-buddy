package net.bytebuddy.instrumentation.method.bytecode.bind.annotation;

import net.bytebuddy.instrumentation.method.MethodDescription;
import net.bytebuddy.instrumentation.method.ParameterDescription;

import java.lang.annotation.*;

/**
 * Parameters that are annotated with this annotation will be assigned by also considering the runtime type of the
 * target parameter. The same is true for a method's return type if a target method is annotated with this annotation.
 * <p>&nbsp;</p>
 * For example, if a source method {@code foo(Object)} is attempted to be bound to
 * {@code bar(@RuntimeType String)}, the binding will attempt to cast the argument of {@code foo} to a {@code String}
 * type before calling {@code bar} with this argument. If this is not possible, a {@link java.lang.ClassCastException}
 * will be thrown at runtime. Similarly, if a method {@code foo} returns a type {@code String} but is bound to a method
 * that returns a type {@code Object}, annotating the target method with {@code @RuntimeType} results in the
 * {@code foo} method casting the target's method return value to {@code String} before returning a value itself.
 *
 * @see net.bytebuddy.instrumentation.MethodDelegation
 * @see TargetMethodAnnotationDrivenBinder
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.METHOD})
public @interface RuntimeType {

    /**
     * A non-instantiable type that allows to check if a method or parameter should consider a runtime type.
     */
    static final class Verifier {

        /**
         * As this is merely a utility method, the constructor is not supposed to be invoked.
         */
        private Verifier() {
            throw new UnsupportedOperationException();
        }

        /**
         * Checks if method return values should be assigned by considering the run time type.
         *
         * @param methodDescription The method of interest.
         * @return {@code true} if the runtime type should be considered for binding the method's return value.
         */
        public static boolean check(MethodDescription methodDescription) {
            return methodDescription.getDeclaredAnnotations().isAnnotationPresent(RuntimeType.class);
        }

        /**
         * Checks if a method parameter should be assigned by considering the run time type.
         *
         * @param parameterDescription The parameter description.
         * @return {@code true} if the runtime type should be considered for binding this parameter.
         */
        public static boolean check(ParameterDescription parameterDescription) {
            return parameterDescription.getDeclaredAnnotations().isAnnotationPresent(RuntimeType.class);
        }
    }
}