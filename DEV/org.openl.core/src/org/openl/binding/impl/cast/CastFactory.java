package org.openl.binding.impl.cast;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.openl.binding.ICastFactory;
import org.openl.binding.IMethodFactory;
import org.openl.binding.exception.AmbiguousMethodException;
import org.openl.binding.impl.cast.ThrowableVoidCast.ThrowableVoid;
import org.openl.cache.GenericKey;
import org.openl.ie.constrainer.ConstrainerObject;
import org.openl.types.IMethodCaller;
import org.openl.types.IOpenClass;
import org.openl.types.IOpenMethod;
import org.openl.types.NullOpenClass;
import org.openl.types.impl.ADynamicClass;
import org.openl.types.impl.DomainOpenClass;
import org.openl.types.java.JavaOpenClass;
import org.openl.util.ClassUtils;

/**
 * Base implementation of {@link ICastFactory} abstraction that used by engine for type conversion operations.
 *
 * @author snshor, Yury Molchan, Marat Kamalov
 */
public class CastFactory implements ICastFactory {

    public static final int NO_CAST_DISTANCE = 1;
    public static final int ALIAS_TO_TYPE_CAST_DISTANCE = 1;

    // USE ONLY EVEN NUMBERS FOR DISTANCES

    public static final int TYPE_TO_ALIAS_CAST_DISTANCE = 2;
    public static final int JAVA_UP_ARRAY_TO_ARRAY_CAST_DISTANCE = 4;
    public static final int JAVA_UP_CAST_DISTANCE = 6;

    public static final int THROWABLE_VOID_CAST_DISTANCE = 8;

    public static final int PRIMITIVE_TO_PRIMITIVE_AUTOCAST_DISTANCE = 10;

    public static final int STRING_ENUM_TO_CAST_DISTANCE = 12;

    public static final int JAVA_BOXING_CAST_DISTANCE = 14;

    public static final int JAVA_BOXING_UP_CAST_DISTANCE = 16;

    public static final int PRIMITIVE_TO_NONPRIMITIVE_AUTOCAST_DISTANCE = 18;

    public static final int JAVA_UNBOXING_CAST_DISTANCE = 22;

    public static final int NONPRIMITIVE_TO_NONPRIMITIVE_AUTOCAST_DISTANCE = 24;

    public static final int ENUM_TO_STRING_CAST_DISTANCE = 26;

    public static final int NONPRIMITIVE_TO_PRIMITIVE_AUTOCAST_DISTANCE = 28;

    public static final int JAVA_DOWN_CAST_DISTANCE = 60;
    public static final int PRIMITIVE_TO_PRIMITIVE_CAST_DISTANCE = 62;
    public static final int NONPRIMITIVE_TO_NONPRIMITIVE_CAST_DISTANCE = 64;
    public static final int NONPRIMITIVE_TO_PRIMITIVE_CAST_DISTANCE = 66;
    public static final int PRIMITIVE_TO_NONPRIMITIVE_CAST_DISTANCE = 68;

    public static final int ARRAY_CAST_DISTANCE = 1000;
    public static final int ONE_ELEMENT_ARRAY_CAST_DISTANCE = 2000;

    public static final String AUTO_CAST_METHOD_NAME = "autocast";
    public static final String CAST_METHOD_NAME = "cast";
    public static final String DISTANCE_METHOD_NAME = "distance";

    /**
     * Method factory object. This factory allows to define cast operations thru java methods.
     */
    private IMethodFactory methodFactory;
    private ICastFactory globalCastFactory;

    /**
     * Internal cache of cast operations.
     */
    private ConcurrentHashMap<Object, IOpenCast> castCache = new ConcurrentHashMap<>();

    /**
     * Default constructor.
     */
    public CastFactory() {
    }

    public void setMethodFactory(IMethodFactory factory) {
        methodFactory = factory;
    }

    @Override
    public IOpenClass findClosestClass(IOpenClass openClass1, IOpenClass openClass2) {
        Iterable<IOpenMethod> autocastMethods = methodFactory.methods(AUTO_CAST_METHOD_NAME);
        return findClosestClass(openClass1, openClass2, this, autocastMethods);
    }

    public static IOpenClass findClosestClass(IOpenClass openClass1,
            IOpenClass openClass2,
            ICastFactory casts,
            Iterable<IOpenMethod> methods) {
        if (NullOpenClass.the.equals(openClass1)) {
            return openClass2;
        }
        if (NullOpenClass.the.equals(openClass2)) {
            return openClass1;
        }
        openClass1 = JavaOpenClass.getOpenClass(openClass1.getInstanceClass()); // AliasDatatypes support
        openClass2 = JavaOpenClass.getOpenClass(openClass2.getInstanceClass());

        Iterator<IOpenMethod> itr = methods.iterator();
        Set<IOpenClass> openClass1Candidates = new LinkedHashSet<>();
        addClassToCandidates(openClass1, openClass1Candidates);
        Set<IOpenClass> openClass2Candidates = new LinkedHashSet<>();
        addClassToCandidates(openClass2, openClass2Candidates);
        while (itr.hasNext()) {
            IOpenMethod method = itr.next();
            if (method.getSignature().getNumberOfParameters() == 2) {
                if (method.getSignature().getParameterType(0).equals(openClass1)) {
                    addClassToCandidates(method.getSignature().getParameterType(1), openClass1Candidates);
                } else {
                    if (method.getSignature().getParameterType(0).getInstanceClass().isPrimitive()) {
                        IOpenClass t = JavaOpenClass.getOpenClass(ClassUtils
                            .primitiveToWrapper(method.getSignature().getParameterType(0).getInstanceClass()));
                        if (t.equals(openClass1)) {
                            addClassToCandidates(method.getSignature().getParameterType(1), openClass1Candidates);
                        }
                    }
                }
                if (method.getSignature().getParameterType(0).equals(openClass2)) {
                    addClassToCandidates(method.getSignature().getParameterType(1), openClass2Candidates);
                } else {
                    if (method.getSignature().getParameterType(0).getInstanceClass().isPrimitive()) {
                        IOpenClass t = JavaOpenClass.getOpenClass(ClassUtils
                            .primitiveToWrapper(method.getSignature().getParameterType(0).getInstanceClass()));
                        if (t.equals(openClass2)) {
                            addClassToCandidates(method.getSignature().getParameterType(1), openClass2Candidates);
                        }
                    }
                }
            }
        }
        openClass1Candidates.retainAll(openClass2Candidates);

        int bestDistance = Integer.MAX_VALUE;
        Set<IOpenClass> closestClasses = new LinkedHashSet<>();
        for (IOpenClass to : openClass1Candidates) {
            int distance = getDistance(casts, openClass1, openClass2, to);

            if (distance > bestDistance) {
                continue;
            }

            if (distance < bestDistance) {
                bestDistance = distance;
                closestClasses.clear();
            }
            closestClasses.add(to);
        }

        openClass1Candidates = closestClasses;

        IOpenClass ret = chooseClosest(casts, openClass1Candidates);

        // If one class is not primitive we use wrapper for prevent NPE
        if (ret != null && openClass1.getInstanceClass() != null && openClass2.getInstanceClass() != null) {
            if (!openClass1.getInstanceClass().isPrimitive() || !openClass2.getInstanceClass().isPrimitive()) {
                if (ret.getInstanceClass().isPrimitive()) {
                    return JavaOpenClass.getOpenClass(ClassUtils.primitiveToWrapper(ret.getInstanceClass()));
                }
            }
        }

        return ret;
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private static IOpenClass chooseClosest(ICastFactory casts, Collection<IOpenClass> openClass1Candidates) {
        IOpenClass ret = null;
        Collection<IOpenClass> notConvertible = new LinkedHashSet<>();
        for (IOpenClass openClass : openClass1Candidates) {
            if (ret == null) {
                ret = openClass;
            } else {
                IOpenCast cast = casts.getCast(ret, openClass);
                if (cast == null || !cast.isImplicit()) {
                    cast = casts.getCast(openClass, ret);
                    if (cast != null && cast.isImplicit()) {
                        // Found narrower candidate. For example Integer is narrower than Double (when convert from
                        // int).
                        ret = openClass;
                    } else {
                        // Two candidate classes are not convertible between each over. For example Float and
                        // BigInteger.
                        // Compare second candidate with remaining candidates later.
                        notConvertible.add(openClass);
                    }
                } else {
                    IOpenCast backCast = casts.getCast(openClass, ret);
                    if (backCast != null && backCast.isImplicit()) {
                        int distance = cast.getDistance();
                        int backDistance = backCast.getDistance();

                        if (distance > backDistance) {
                            // Assume that a cast to openClass is narrower than a cast to ret.
                            ret = openClass;
                        } else if (distance == backDistance) {
                            // We have a collision.
                            String message = "Can't find closest cast: have two candidate classes with same cast distance: " + ret
                                .getName() + " and " + openClass.getName();
                            throw new IllegalStateException(message);
                        } else {
                            // Previous candidate is narrower. Keep it.
                        }
                    } else {
                        // Previous candidate is narrower. Keep it.
                    }
                }
            }
        }

        if (!notConvertible.isEmpty()) {
            Collection<IOpenClass> newCandidates = new LinkedHashSet<>();
            newCandidates.add(ret);
            newCandidates.addAll(notConvertible);

            if (newCandidates.size() == openClass1Candidates.size()) {
                // Can't filter out classes to choose a closest. Prevent infinite recursion.
                String message = "Can't find closest cast: have several candidate classes not convertible between each over: " + Arrays
                    .toString(newCandidates.toArray());
                throw new IllegalStateException(message);
            }

            return chooseClosest(casts, newCandidates);
        }

        return ret;
    }

    private static int getDistance(ICastFactory casts, IOpenClass from1, IOpenClass from2, IOpenClass to) {
        IOpenCast cast1 = casts.getCast(from1, to);
        IOpenCast cast2 = casts.getCast(from2, to);

        int distance;
        if (cast1 == null || !cast1.isImplicit() || cast2 == null || !cast2.isImplicit()) {
            distance = Integer.MAX_VALUE;
        } else {
            distance = Math.max(cast1.getDistance(), cast2.getDistance());
        }
        return distance;
    }

    private static void addClassToCandidates(IOpenClass openClass, Set<IOpenClass> candidates) {
        if (openClass.getInstanceClass() != null) {
            candidates.add(openClass);
            if (openClass.getInstanceClass().isPrimitive()) {
                candidates.add(JavaOpenClass.getOpenClass(ClassUtils.primitiveToWrapper(openClass.getInstanceClass())));
            } else {
                Class<?> t = ClassUtils.wrapperToPrimitive(openClass.getInstanceClass());
                if (t != null) {
                    candidates.add(JavaOpenClass.getOpenClass(t));
                }
            }
        }
    }

    /**
     * Gets cast operation for given types. This is method is using internal cache for cast operations.
     *
     * @param from from type
     * @param to to type
     *
     * @return cast operation if it have been found; null - otherwise
     */
    @Override
    public IOpenCast getCast(IOpenClass from, IOpenClass to) {
        /* BEGIN: This is very cheap operations, so no needs to chache it */
        if (from == to || from.equals(to)) {
            return JavaNoCast.getInstance();
        }

        if (to == NullOpenClass.the) {
            return null;
        }

        if (from == NullOpenClass.the) {
            if (isPrimitive(to)) {
                return null;
            } else {
                return JavaUpCast.getInstance();
            }
        }

        if (ThrowableVoid.class.equals(from.getInstanceClass())) {
            return ThrowableVoidCast.getInstance();
        }
        /* END: This is very cheap operations, so no needs to cache it */
        Object key = GenericKey.getInstance(from, to);
        IOpenCast cast = castCache.get(key);
        if (cast == CastNotFound.getInstance()) {
            return null;
        }
        if (cast != null) {
            return cast;
        }

        IOpenCast typeCast = findCast(from, to);
        if (typeCast == null) {
            typeCast = CastNotFound.getInstance();
        }

        IOpenCast saved = castCache.putIfAbsent(key, typeCast);
        if (saved != null) {
            // Concurrent modification happens
            // Return saved instance
            typeCast = saved;
        }

        return typeCast == CastNotFound.getInstance() ? null : typeCast;
    }

    private IOpenCast findCast(IOpenClass from, IOpenClass to) {
        IOpenCast typeCast = findArrayCast(from, to);
        if (typeCast != null) {
            return typeCast;
        }

        typeCast = findAliasCast(from, to);
        IOpenCast javaCast = findJavaCast(from, to);
        // Select minimum between alias cast and java cast
        typeCast = selectBetterCast(from, to, typeCast, javaCast);

        IOpenCast methodBasedCast = findMethodBasedCast(from, to, methodFactory);
        typeCast = selectBetterCast(from, to, typeCast, methodBasedCast);

        if (typeCast == null) {
            typeCast = findOneElementArrayCast(from, to);
        }

        return typeCast;
    }

    private IOpenCast selectBetterCast(IOpenClass from, IOpenClass to, IOpenCast castA, IOpenCast castB) {
        if (castA == null && castB == null) {
            return null;
        }
        if (castA == null) {
            return castB;
        }
        if (castB == null) {
            return castA;
        }

        int distanceA = castA.getDistance();
        int distanceB = castB.getDistance();

        return distanceA > distanceB ? castB : castA;
    }

    private IOpenCast getUpCast(Class<?> from, Class<?> to) {
        if (from.isArray() && to.isArray()) {
            return JavaUpArrayCast.getInstance();
        }
        return JavaUpCast.getInstance();
    }

    private IOpenCast findArrayCast(IOpenClass from, IOpenClass to) {
        if (!to.isArray()) {
            return null;
        }
        Class<?> fromClass = from.getInstanceClass();
        if (to.isAssignableFrom(from) && !(to instanceof DomainOpenClass)) {
            // Improve for up cast
            return getUpCast(fromClass, to.getInstanceClass());
        }
        if (Object.class.equals(fromClass)) {
            // Special case for casting when:
            // Object from = new SomeType[x]
            // SomeType[] to = from
            return new JavaDownCast(to, this);
        }
        if (!from.isArray()) {
            return null;
        }

        IOpenClass t = to.getComponentClass();
        IOpenClass f = from.getComponentClass();
        if (!f.isArray() && t.isArray()) {
            // to prevent Obj[] -> Obj[][] because of findOneElementArrayCast
            return null;
        }
        IOpenCast arrayElementCast = getCast(f, t);
        if (arrayElementCast != null) {
            return new ArrayCast(t, arrayElementCast);
        }
        return null;
    }

    private IOpenCast findOneElementArrayCast(IOpenClass from, IOpenClass to) {
        if (!from.isArray() && to.isArray() && !to.getComponentClass().isArray()) {
            IOpenClass componentClass = to.getComponentClass();
            IOpenCast cast = getCast(from, componentClass);
            if (cast != null) {
                return new OneElementArrayCast(componentClass, cast);
            }
        }
        return null;
    }

    public ICastFactory getGlobalCastFactory() {
        if (globalCastFactory == null) {
            return this;
        }
        return globalCastFactory;
    }

    public void setGlobalCastFactory(ICastFactory globalCastFactory) {
        this.globalCastFactory = globalCastFactory;
    }

    /**
     * Checks that instance class of open class is primitive.
     *
     * @param openClass type to check
     * @return <code>true</code> if instance class is primitive type; <code>false</code> - otherwise
     */
    private boolean isPrimitive(IOpenClass openClass) {
        return openClass != null && openClass.getInstanceClass() != null && openClass.getInstanceClass().isPrimitive();
    }

    /**
     * Finds appropriate cast type operation using cast rules of java language. If result type is not java class
     * <code>null</code> will be returned.
     *
     * @param from from type
     * @param to to type
     * @return cast operation if conversion is found; null - otherwise
     */
    private IOpenCast findJavaCast(IOpenClass from, IOpenClass to) {

        // Try to find cast using instance classes.
        //
        Class<?> fromClass = from.getInstanceClass();
        Class<?> toClass = to.getInstanceClass();

        if (fromClass == toClass && from != to && from instanceof ADynamicClass && to instanceof ADynamicClass) { // Dynamic
            // classes
            // with
            // the
            // same
            // instance
            // class
            return null;
        }

        if (ConstrainerObject.class.isAssignableFrom(fromClass)) {
            return null;
        }

        if (toClass.isAssignableFrom(fromClass)) {
            return getUpCast(fromClass, toClass);
        }

        IOpenCast typeCast = findBoxingCast(from, to);

        if (typeCast != null) {
            return typeCast;
        }

        typeCast = findUnBoxingCast(from, to);

        if (typeCast != null) {
            return typeCast;
        }

        if (isAllowJavaDowncast(fromClass, toClass)) {
            return new JavaDownCast(to, getGlobalCastFactory());
        }

        if (fromClass.isEnum() && toClass == String.class) {
            return EnumToStringCast.getInstance();
        }
        if (String.class.equals(fromClass) && toClass.isEnum()) {
            return new StringToEnumCast(toClass);
        }
        return null;
    }

    /**
     * Finds appropriate auto boxing (primitive to wrapper object) cast operation.
     *
     * @param from primitive type
     * @param to wrapper type
     * @return auto boxing cast operation if conversion is found; null - otherwise
     */
    private IOpenCast findBoxingCast(IOpenClass from, IOpenClass to) {

        if (from == null || to == null || !isPrimitive(from) || isPrimitive(to)) {
            return null;
        }

        Class<?> fromClass = from.getInstanceClass();
        Class<?> toClass = to.getInstanceClass();

        if (fromClass.equals(ClassUtils.wrapperToPrimitive(toClass))) {
            return JavaBoxingCast.getInstance();
        }

        if (toClass.isAssignableFrom(ClassUtils.primitiveToWrapper(fromClass))) {
            return JavaBoxingUpCast.getInstance();
        }

        // Apache ClassUtils has error in 2.6
        if (void.class.equals(fromClass) && Void.class.equals(toClass)) {
            return JavaBoxingCast.getInstance();
        }

        return null;
    }

    /**
     * Finds appropriate unboxing (wrapper object to primitive) cast operation.
     *
     * @param from wrapper type
     * @param to primitive type
     * @return unboxing cast operation if conversion is found; null - otherwise
     */
    private IOpenCast findUnBoxingCast(IOpenClass from, IOpenClass to) {

        if (from == null || to == null || isPrimitive(from) || !isPrimitive(to)) {
            return null;
        }

        Class<?> fromClass = from.getInstanceClass();
        Class<?> toClass = to.getInstanceClass();

        if (toClass.equals(ClassUtils.wrapperToPrimitive(fromClass))) {
            return JavaUnboxingCast.getInstance();
        }

        // Apache ClassUtils has error in 2.6
        if (Void.class.equals(fromClass) && void.class.equals(toClass)) {
            return JavaUnboxingCast.getInstance();
        }

        return null;
    }

    /**
     * Finds cast operation for alias types. If both types are not alias types <code>null</code> will be returned.
     *
     * @param from from type
     * @param to to type
     * @return alias cast operation if conversion is found; null - otherwise
     */
    private IOpenCast findAliasCast(IOpenClass from, IOpenClass to) {
        if (!from.isArray() && (from instanceof DomainOpenClass || to instanceof DomainOpenClass)) {

            if (from instanceof DomainOpenClass && !(to instanceof DomainOpenClass) && to.isAssignableFrom(from)) {
                return AliasToTypeCast.getInstance();
            }

            if (to instanceof DomainOpenClass && !(from instanceof DomainOpenClass) && from.isAssignableFrom(to)) {
                return new TypeToAliasCast(to);
            }

            if (from instanceof DomainOpenClass && to.getInstanceClass().isAssignableFrom(from.getClass())) { // This is
                                                                                                              // not
                                                                                                              // typo
                return JavaUpCast.getInstance();
            }

            if (from instanceof DomainOpenClass && !(to instanceof DomainOpenClass)) {
                IOpenCast openCast = this.findCast(JavaOpenClass.getOpenClass(from.getInstanceClass()), to);
                if (openCast != null) {
                    return new AliasToTypeCast(openCast);
                }
            }

            if (to instanceof DomainOpenClass && !(from instanceof DomainOpenClass)) {
                IOpenCast openCast = this.findCast(from, JavaOpenClass.getOpenClass(to.getInstanceClass()));
                if (openCast != null) {
                    return new TypeToAliasCast(to, openCast);
                }
            }
        }

        return null;
    }

    /**
     * Finds cast operation using {@link IMethodFactory} object.
     *
     * @param from from type
     * @param to to type
     * @param methodFactory {@link IMethodFactory} object
     * @return cast operation
     */
    private IOpenCast findMethodBasedCast(IOpenClass from, IOpenClass to, IMethodFactory methodFactory) {

        IOpenCast typeCast = findMethodCast(from, to, methodFactory);

        if (typeCast != null) {
            return typeCast;
        }

        typeCast = findMethodCast(from, to, from);

        if (typeCast != null) {
            return typeCast;
        }

        typeCast = findMethodCast(from, to, to);

        if (typeCast != null) {
            return typeCast;
        }

        return null;
    }

    /**
     * Finds cast operation using {@link IMethodFactory} object.
     *
     * @param from from type
     * @param to to type
     * @param methodFactory {@link IMethodFactory} object
     * @return cast operation
     */
    private IOpenCast findMethodCast(IOpenClass from, IOpenClass to, IMethodFactory methodFactory) {

        if (methodFactory == null) {
            return null;
        }

        // Is auto cast ?
        boolean auto = true;
        int distance;
        if (from.getInstanceClass().isPrimitive() && !to.getInstanceClass().isPrimitive()) {
            distance = PRIMITIVE_TO_NONPRIMITIVE_AUTOCAST_DISTANCE;
        } else if (!from.getInstanceClass().isPrimitive() && to.getInstanceClass().isPrimitive()) {
            distance = NONPRIMITIVE_TO_PRIMITIVE_AUTOCAST_DISTANCE;
        } else if (!from.getInstanceClass().isPrimitive() && !to.getInstanceClass().isPrimitive()) {
            distance = NONPRIMITIVE_TO_NONPRIMITIVE_AUTOCAST_DISTANCE;
        } else {
            distance = PRIMITIVE_TO_PRIMITIVE_AUTOCAST_DISTANCE;
        }

        // Matching method
        IMethodCaller castCaller = null;

        // To object null value
        Object toNullObject = to.nullObject();

        IOpenClass fromOpenClass = from;
        IOpenClass toOpenClass = to;

        Class<?> primitiveClassFrom = ClassUtils.wrapperToPrimitive(from.getInstanceClass());
        Class<?> primitiveClassTo = ClassUtils.wrapperToPrimitive(to.getInstanceClass());

        try {
            // Try to find matching auto cast method
            castCaller = methodFactory.getMethod(AUTO_CAST_METHOD_NAME, new IOpenClass[] { from, to });

            if (castCaller == null) {
                // If from parameter is wrapper for primitive type try to find
                // auto cast method using 'from' as primitive type. In this case
                // we are emulate 2 operations: 1) unboxing operation 2)
                // autocast operation.
                // For example:
                // <code>
                // Integer a = 1;
                // double d = a;
                // </code>
                // For OpenL we are omitting the check that 'to' type must be
                // primitive type for our case to simplify understanding type
                // operations in
                // engine by end-user.
                //
                if (primitiveClassFrom != null) {
                    IOpenClass wrapperOpenClassFrom = JavaOpenClass.getOpenClass(primitiveClassFrom);
                    fromOpenClass = wrapperOpenClassFrom;
                    toOpenClass = to;
                    castCaller = methodFactory.getMethod(AUTO_CAST_METHOD_NAME,
                        new IOpenClass[] { wrapperOpenClassFrom, to });
                }
            }

            if (castCaller == null) {
                // If to parameter is wrapper for primitive type try to find
                // auto cast method using 'to' as primitive type. In this case
                // we are emulate 2 operations: 1) autocast operation,
                // 2) boxing operation.
                // For example:
                // <code>
                // int a = 1;
                // Double d = a;
                // </code>
                // For OpenL we are omitting the check that 'from' type must be
                // primitive type for our case to simplify understanding type
                // operations in
                // engine by end-user.
                //
                if (primitiveClassTo != null) {
                    IOpenClass wrapperOpenClassTo = JavaOpenClass.getOpenClass(primitiveClassTo);
                    castCaller = methodFactory.getMethod(AUTO_CAST_METHOD_NAME,
                        new IOpenClass[] { from, wrapperOpenClassTo });
                    fromOpenClass = from;
                    toOpenClass = wrapperOpenClassTo;
                    toNullObject = wrapperOpenClassTo.nullObject();
                }
            }

            if (castCaller == null && primitiveClassFrom != null && primitiveClassTo != null) {
                IOpenClass wrapperOpenClassFrom = JavaOpenClass.getOpenClass(primitiveClassFrom);
                IOpenClass wrapperOpenClassTo = JavaOpenClass.getOpenClass(primitiveClassTo);
                fromOpenClass = wrapperOpenClassFrom;
                toOpenClass = wrapperOpenClassTo;
                castCaller = methodFactory.getMethod(AUTO_CAST_METHOD_NAME,
                    new IOpenClass[] { wrapperOpenClassFrom, wrapperOpenClassTo });
            }
        } catch (AmbiguousMethodException ex) {
            // Ignore exception.
            //
        }

        // If appropriate auto cast method is not found try to find explicit
        // cast method.
        //
        if (castCaller == null) {
            auto = false;
            try {
                castCaller = methodFactory.getMethod(CAST_METHOD_NAME, new IOpenClass[] { from, to });
                if (from.getInstanceClass().isPrimitive() && !to.getInstanceClass().isPrimitive()) {
                    distance = PRIMITIVE_TO_NONPRIMITIVE_CAST_DISTANCE;
                } else if (!from.getInstanceClass().isPrimitive() && to.getInstanceClass().isPrimitive()) {
                    distance = NONPRIMITIVE_TO_PRIMITIVE_CAST_DISTANCE;
                } else if (!from.getInstanceClass().isPrimitive() && !to.getInstanceClass().isPrimitive()) {
                    distance = NONPRIMITIVE_TO_NONPRIMITIVE_CAST_DISTANCE;
                } else {
                    distance = PRIMITIVE_TO_PRIMITIVE_CAST_DISTANCE;
                }

                if (castCaller == null) {
                    if (primitiveClassFrom != null) {
                        IOpenClass wrapperOpenClassFrom = JavaOpenClass.getOpenClass(primitiveClassFrom);
                        fromOpenClass = wrapperOpenClassFrom;
                        toOpenClass = to;
                        castCaller = methodFactory.getMethod(CAST_METHOD_NAME,
                            new IOpenClass[] { wrapperOpenClassFrom, to });
                    }
                }

                if (castCaller == null) {
                    if (primitiveClassTo != null) {
                        IOpenClass wrapperOpenClassTo = JavaOpenClass.getOpenClass(primitiveClassTo);
                        castCaller = methodFactory.getMethod(CAST_METHOD_NAME,
                            new IOpenClass[] { from, wrapperOpenClassTo });
                        fromOpenClass = from;
                        toOpenClass = wrapperOpenClassTo;
                        toNullObject = wrapperOpenClassTo.nullObject();
                    }
                }

                if (castCaller == null) {
                    if (primitiveClassFrom != null && primitiveClassTo != null) {
                        IOpenClass wrapperOpenClassFrom = JavaOpenClass.getOpenClass(primitiveClassFrom);
                        IOpenClass wrapperOpenClassTo = JavaOpenClass.getOpenClass(primitiveClassTo);
                        fromOpenClass = wrapperOpenClassFrom;
                        toOpenClass = wrapperOpenClassTo;
                        castCaller = methodFactory.getMethod(CAST_METHOD_NAME,
                            new IOpenClass[] { wrapperOpenClassFrom, wrapperOpenClassTo });
                    }
                }

            } catch (AmbiguousMethodException ex) {
                // Ignore exception.
                //
            }
        }

        if (castCaller == null) {
            return null;
        }

        IMethodCaller distanceCaller = null;

        try {
            distanceCaller = methodFactory.getMethod(DISTANCE_METHOD_NAME,
                new IOpenClass[] { fromOpenClass, toOpenClass });
        } catch (AmbiguousMethodException ignored) {
        }

        if (distanceCaller != null) {
            distance = (Integer) distanceCaller
                .invoke(null, new Object[] { fromOpenClass.nullObject(), toOpenClass.nullObject() }, null);
        }

        return new MethodBasedCast(castCaller, auto, distance, toNullObject);
    }

    /**
     * The following conversions are called the narrowing reference conversions:
     *
     * From any class type S to any class type T, provided that S is a superclass of T. (An important special case is
     * that there is a narrowing conversion from the class type Object to any other class type.) From any class type S
     * to any interface type K, provided that S is not final and does not implement K. (An important special case is
     * that there is a narrowing conversion from the class type Object to any interface type.) From type Object to any
     * array type. From type Object to any interface type. From any interface type J to any class type T that is not
     * final. From any interface type J to any class type T that is final, provided that T implements J. From any
     * interface type J to any interface type K, provided that J is not a subinterface of K and there is no method name
     * m such that J and K both contain a method named m with the same signature but different return types. From any
     * array type SC[] to any array type TC[], provided that SC and TC are reference types and there is a narrowing
     * conversion from SC to TC.
     *
     * @link http://java.sun.com/docs/books/jls/second_edition/html/conversions.doc .html
     * @param from from type
     * @param to to type
     * @return <code>true</code> is downcast operation is allowed for given types; <code>false</code> - otherwise
     */
    private boolean isAllowJavaDowncast(Class<?> from, Class<?> to) {

        if (from.isAssignableFrom(to)) {
            return true;
        }

        if (!from.isPrimitive() && !Modifier.isFinal(from.getModifiers()) && to.isInterface()) {
            return true;
        }

        return !to.isPrimitive() && !Modifier.isFinal(to.getModifiers()) && from.isInterface();

    }

    public IMethodFactory getMethodFactory() {
        return methodFactory;
    }
}
