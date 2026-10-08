package io.github.hellotta.clauac.simulation.session;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import io.github.hellotta.clauac.simulation.player.ClientContext;
import java.io.Serial;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractQueue;
import java.util.AbstractSequentialList;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

// - The complete state of the objects a player's tick changes, saved so that the tick can run again from the same -
// - start (see PlayConnection.tickLocalPlayer). Starting at the roots, every object the roots own is saved: its -
// - fields by reflection, an array element by element, an atomic value through its accessors. The collections and -
// - maps of the JDK, whose fields cannot be reached, go through their own interfaces; every other collection -
// - (fastutil, Guava, Minecraft's own) is saved field by field, so that a hash map gets back the exact layout its -
// - iteration order comes from, which decides the order in which sums over it are rounded. Restoring puts every -
// - owned object back as the same instance with its old contents and sets every field that is not final back to its -
// - old reference, so the references between these objects stay as they were. Values that cannot change -
// - (positions, registry entries, block states, components, immutable collections, lambdas) and the objects the -
// - roots only use (the level, other entities and block entities, the sandbox's connection) are kept as references -
// - and not saved themselves. A snapshot that meets an object it cannot save throws a SnapshotException, so that an -
// - incomplete snapshot is never used -
final class StateSnapshot {

    // - Far more objects than a player's state holds; a walk that gets here went into state the player only uses -
    private static final int MAX_OBJECTS = 200_000;
    // - A player's state holds about 300 objects; the snapshot's maps start large enough for them -
    private static final int EXPECTED_OBJECTS = 512;
    // - The instance fields of each class and its superclasses, and what a snapshot does with each (see layoutOf) -
    private static final Map<Class<?>, ClassLayout> LAYOUTS = new ConcurrentHashMap<>();
    // - Types whose instances never change once made, or that belong to the registries every entity shares -
    private static final List<Class<?>> VALUE_TYPES = List.of(
            Integer.class, Long.class, Float.class, Double.class, Short.class, Byte.class, BigInteger.class, BigDecimal.class,
            Boolean.class, Character.class, String.class, Enum.class, Class.class, UUID.class,
            Optional.class, OptionalInt.class, OptionalLong.class, OptionalDouble.class,
            ImmutableCollection.class, ImmutableMap.class, ImmutableMultimap.class,
            Vec3.class, Vec2.class, AABB.class, ChunkPos.class, SectionPos.class, GlobalPos.class,
            Component.class, Style.class, Holder.class, HolderSet.class, ResourceKey.class, Identifier.class, TagKey.class,
            StateHolder.class, Item.class, Block.class, Fluid.class, EntityType.class, BlockEntityType.class, MobEffect.class,
            Attribute.class, AttributeModifier.class, SoundEvent.class, DataComponentType.class, DataComponentPatch.class,
            Enchantment.class, DamageSource.class, DamageType.class, Potion.class, Recipe.class, RecipeHolder.class,
            MemoryModuleType.class, SensorType.class, Activity.class, MenuType.class, PermissionSet.class,
            EntityDimensions.class, GameProfile.class
    );
    // - Objects the roots use but do not own: the level with everything in it, the registries and the sandbox's own -
    // - connection. Other entities are shared as well unless they are roots -
    private static final List<Class<?>> SHARED_TYPES = List.of(
            Level.class, BlockEntity.class, EntityInLevelCallback.class, AttributeSupplier.class, Registry.class,
            HolderLookup.Provider.class, HolderGetter.class, ClientContext.class, org.slf4j.Logger.class, Thread.class,
            ClassLoader.class
    );
    // - Packages of the sandbox itself whose objects are shared: the connection, the level, the registries -
    private static final List<String> SHARED_PACKAGES = List.of(
            "io.github.hellotta.clauac.simulation.session.",
            "io.github.hellotta.clauac.simulation.world.",
            "io.github.hellotta.clauac.simulation.registry."
    );
    // - Superclasses of the JDK whose fields do not matter to a snapshot of a subclass (see layoutOf) -
    private static final Set<Class<?>> STATELESS_JDK_SUPERCLASSES = Set.of(
            AbstractCollection.class, AbstractList.class, AbstractSequentialList.class, AbstractSet.class, AbstractQueue.class, AbstractMap.class
    );
    // - Unmodifiable views and immutable collections of the JDK, recognised by their implementation classes -
    private static final List<String> IMMUTABLE_JDK_COLLECTIONS = List.of(
            "java.util.ImmutableCollections$",
            "java.util.Collections$Unmodifiable",
            "java.util.Collections$Empty",
            "java.util.Collections$Singleton"
    );
    // - How a snapshot treats the objects of each class, worked out once per class (see classify) -
    private static final ClassValue<ClassKind> CLASS_KINDS = new ClassValue<>() {
        @Override
        protected ClassKind computeValue(Class<?> type) {
            return classify(type);
        }
    };

    private final Set<Object> roots;
    private final Map<Object, SavedState> states = new IdentityHashMap<>(EXPECTED_OBJECTS);
    // - How each object the snapshot queued was reached, to name it when something goes wrong -
    private final Map<Object, Origin> origins = new IdentityHashMap<>(EXPECTED_OBJECTS);
    // - The class this snapshot looked up last, since the elements of a collection tend to share their class -
    private @Nullable ClassKind lastLookup;

    private StateSnapshot(Collection<?> roots) {
        this.roots = Collections.newSetFromMap(new IdentityHashMap<>());
        this.roots.addAll(roots);
    }

    // - Saves everything the roots own. Every object is queued once, with the kind it was found to have -
    static StateSnapshot capture(Collection<?> roots) throws SnapshotException {
        StateSnapshot snapshot = new StateSnapshot(roots);
        Deque<Queued> pending = new ArrayDeque<>();
        for (Object root : roots) {
            if (!snapshot.origins.containsKey(root)) {
                snapshot.origins.put(root, new Origin(null, Step.ROOT, null, 0));
                pending.add(new Queued(root, snapshot.kindOf(root)));
            }
        }
        while (!pending.isEmpty()) {
            Queued queued = pending.poll();
            if (snapshot.states.size() >= MAX_OBJECTS) {
                throw new SnapshotException("more than " + MAX_OBJECTS + " objects, the last at " + snapshot.pathOf(queued.object()));
            }
            snapshot.states.put(queued.object(), snapshot.save(queued.object(), queued.kind(), pending));
        }
        return snapshot;
    }

    // - How many objects the snapshot saved -
    int objectCount() {
        return this.states.size();
    }

    // - Puts every saved object back: first the fields, arrays and atomic values, then the contents of collections -
    // - and maps, whose hash codes may depend on the objects restored first -
    void restore() throws SnapshotException {
        for (SavedState state : this.states.values()) {
            if (!holdsElements(state)) {
                state.restore();
            }
        }
        for (SavedState state : this.states.values()) {
            if (holdsElements(state)) {
                state.restore();
            }
        }
    }

    // - The saved contents of a list, another collection or a map -
    private static boolean holdsElements(SavedState state) {
        return state instanceof ListState<?> || state instanceof CollectionState<?> || state instanceof MapState<?, ?>;
    }

    // - Where a later snapshot of the same roots differs from this one, or null when every saved value is the same. -
    // - Objects made after this snapshot are compared by their contents -
    @Nullable String firstDifference(StateSnapshot later) throws SnapshotException {
        Set<Object> compared = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object root : this.roots) {
            String difference = this.difference(root, later, root, compared);
            if (difference != null) {
                return difference;
            }
        }
        return null;
    }

    private @Nullable String difference(Object earlierObject, StateSnapshot later, Object laterObject, Set<Object> compared) throws SnapshotException {
        if (!compared.add(earlierObject)) {
            return null;
        }
        SavedState earlierState = this.states.get(earlierObject);
        SavedState laterState = later.states.get(laterObject);
        if (earlierState == null || laterState == null || earlierState.getClass() != laterState.getClass()) {
            return this.pathOf(earlierObject) + " is saved in only one of the snapshots";
        }
        Object[] earlierValues = earlierState.values();
        Object[] laterValues = laterState.values();
        if (earlierValues.length != laterValues.length) {
            return this.pathOf(earlierObject) + " holds " + earlierValues.length + " values, later " + laterValues.length;
        }
        for (int index = 0; index < earlierValues.length; index++) {
            Object earlierValue = earlierValues[index];
            Object laterValue = laterValues[index];
            boolean earlierSaved = earlierValue != null && this.states.containsKey(earlierValue);
            boolean laterSaved = laterValue != null && later.states.containsKey(laterValue);
            if (earlierSaved && laterSaved) {
                String difference = this.difference(earlierValue, later, laterValue, compared);
                if (difference != null) {
                    return difference;
                }
            } else if (earlierSaved != laterSaved || !this.sameValue(earlierValue, laterValue, Collections.newSetFromMap(new IdentityHashMap<>()))) {
                return this.pathOf(earlierObject) + " " + earlierState.nameOf(index) + ": " + earlierValue + " became " + laterValue;
            }
        }
        return null;
    }

    // - Whether two values a snapshot keeps as references are the same. A value made anew in each run is the same -
    // - when it is equal, or, for a class outside the JDK whose equals compares identity (a record holding one of -
    // - them included, like EntityDimensions with its EntityAttachments), when it is the same field by field. An -
    // - object the roots only use has to be the very same object -
    private boolean sameValue(@Nullable Object earlier, @Nullable Object later, Set<Object> comparing) throws SnapshotException {
        if (Objects.equals(earlier, later)) {
            return true;
        }
        if (earlier == null || later == null || earlier.getClass() != later.getClass()) {
            return false;
        }
        Class<?> type = earlier.getClass();
        if (type.isArray()) {
            int length = Array.getLength(earlier);
            if (length != Array.getLength(later)) {
                return false;
            }
            for (int index = 0; index < length; index++) {
                if (!this.sameValue(Array.get(earlier, index), Array.get(later, index), comparing)) {
                    return false;
                }
            }
            return true;
        }
        if (CLASS_KINDS.get(type).jdk() || type.isHidden() || type.isSynthetic() || this.kindOf(earlier) == Kind.SHARED) {
            return false;
        }
        if (!comparing.add(earlier)) {
            return true;
        }
        for (Field field : layoutOf(type).fields()) {
            try {
                if (!this.sameValue(field.get(earlier), field.get(later), comparing)) {
                    return false;
                }
            } catch (IllegalAccessException exception) {
                throw new SnapshotException("cannot read " + field + " to compare two values: " + exception);
            }
        }
        return true;
    }

    private SavedState save(Object object, Kind kind, Deque<Queued> pending) throws SnapshotException {
        return switch (kind) {
            case ARRAY -> this.saveArray(object, pending);
            case LIST -> this.saveList((List<?>) object, pending);
            case COLLECTION -> this.saveCollection((Collection<?>) object, pending);
            case MAP -> this.saveMap((Map<?, ?>) object, pending);
            case ATOMIC -> saveAtomic(object);
            case OBJECT -> this.saveFields(object, pending);
            case UNSUPPORTED -> throw new SnapshotException("cannot save the " + object.getClass().getName() + " at " + this.pathOf(object));
            case VALUE, SHARED -> throw new SnapshotException("a " + kind + " at " + this.pathOf(object) + " was queued to be saved");
        };
    }

    // - Queues a referenced object when the roots own it; every queued object has its origin, so that is all there is -
    // - to check. The kind is looked up unless the declared type of a field decided it already. An object of a class -
    // - the snapshot cannot save is queued as well, and fails with its whole path -
    private void follow(@Nullable Object value, @Nullable Kind knownKind, Deque<Queued> pending, Object owner, Step step, @Nullable Field field, int index) {
        if (value == null) {
            return;
        }
        Kind kind = knownKind != null ? knownKind : this.kindOf(value);
        if (kind == Kind.VALUE || kind == Kind.SHARED || this.origins.containsKey(value)) {
            return;
        }
        this.origins.put(value, new Origin(owner, step, field, index));
        pending.add(new Queued(value, kind));
    }

    private SavedState saveFields(Object object, Deque<Queued> pending) throws SnapshotException {
        ClassLayout layout = layoutOf(object.getClass());
        Field[] fields = layout.fields();
        FieldUse[] uses = layout.uses();
        Kind[] declaredKinds = layout.declaredKinds();
        Object[] values = new Object[fields.length];
        for (int index = 0; index < fields.length; index++) {
            FieldUse use = uses[index];
            if (use == FieldUse.SKIP) {
                continue;
            }
            Field field = fields[index];
            Object value;
            try {
                value = field.get(object);
            } catch (IllegalAccessException exception) {
                throw new SnapshotException("cannot read " + field + " at " + this.pathOf(object) + ": " + exception);
            }
            values[index] = value;
            if (use == FieldUse.FOLLOW && value != null) {
                Kind kind = declaredKinds[index] != null ? declaredKinds[index] : this.kindOfFieldValue(value, layout, index);
                this.follow(value, kind, pending, object, Step.FIELD, field, index);
            }
        }
        return new FieldState(object, layout, values);
    }

    private SavedState saveArray(Object array, Deque<Queued> pending) {
        int length = Array.getLength(array);
        Object copy = Array.newInstance(array.getClass().getComponentType(), length);
        System.arraycopy(array, 0, copy, 0, length);
        if (!array.getClass().getComponentType().isPrimitive()) {
            Object[] elements = (Object[]) copy;
            for (int index = 0; index < length; index++) {
                this.follow(elements[index], null, pending, array, Step.ELEMENT, null, index);
            }
        }
        return new ArrayState(array, copy);
    }

    // - The collections below are saved with their own element types, so that restoring them needs no unchecked cast -
    private <E> SavedState saveList(List<E> list, Deque<Queued> pending) {
        List<E> elements = new ArrayList<>(list);
        for (int index = 0; index < elements.size(); index++) {
            this.follow(elements.get(index), null, pending, list, Step.ELEMENT, null, index);
        }
        return new ListState<>(list, elements);
    }

    private <E> SavedState saveCollection(Collection<E> collection, Deque<Queued> pending) {
        List<E> elements = new ArrayList<>(collection);
        for (int index = 0; index < elements.size(); index++) {
            this.follow(elements.get(index), null, pending, collection, Step.MEMBER, null, index);
        }
        return new CollectionState<>(collection, elements);
    }

    private <K, V> SavedState saveMap(Map<K, V> map, Deque<Queued> pending) throws SnapshotException {
        int size = map.size();
        List<K> keys = new ArrayList<>(size);
        List<V> values = new ArrayList<>(size);
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (keys.size() == size) {
                throw new SnapshotException("the map at " + this.pathOf(map) + " changed while it was saved");
            }
            this.follow(entry.getKey(), null, pending, map, Step.KEY, null, keys.size());
            this.follow(entry.getValue(), null, pending, map, Step.VALUE, null, keys.size());
            keys.add(entry.getKey());
            values.add(entry.getValue());
        }
        if (keys.size() != size) {
            throw new SnapshotException("the map at " + this.pathOf(map) + " holds fewer entries than its size");
        }
        return new MapState<>(map, keys, values);
    }

    private static SavedState saveAtomic(Object atomic) {
        return switch (atomic) {
            case AtomicLong atomicLong -> new AtomicState(atomicLong, atomicLong.get());
            case AtomicInteger atomicInteger -> new AtomicState(atomicInteger, atomicInteger.get());
            case AtomicBoolean atomicBoolean -> new AtomicState(atomicBoolean, atomicBoolean.get());
            case AtomicReference<?> atomicReference -> saveAtomicReference(atomicReference);
            default -> throw new IllegalArgumentException("not an atomic value: " + atomic.getClass().getName());
        };
    }

    private static <T> SavedState saveAtomicReference(AtomicReference<T> reference) {
        return new AtomicReferenceState<>(reference, reference.get());
    }

    // - The kind of an object -
    private Kind kindOf(Object value) {
        Class<?> type = value.getClass();
        ClassKind last = this.lastLookup;
        ClassKind classKind = last != null && last.type() == type ? last : CLASS_KINDS.get(type);
        this.lastLookup = classKind;
        return this.kindOf(value, classKind);
    }

    // - The kind of a value a field holds, through the class that field held last: most fields always hold objects of -
    // - one class. The layout is shared by all snapshots and the remembered class replaced as a whole, so two -
    // - snapshots racing on it cost at most a lookup -
    private Kind kindOfFieldValue(Object value, ClassLayout layout, int index) {
        Class<?> type = value.getClass();
        ClassKind seen = layout.seenClasses()[index];
        if (seen == null || seen.type() != type) {
            seen = CLASS_KINDS.get(type);
            layout.seenClasses()[index] = seen;
        }
        return this.kindOf(value, seen);
    }

    // - Only for an entity it matters whether it is one of the roots -
    private Kind kindOf(Object value, ClassKind classKind) {
        return classKind.kind() != classKind.rootKind() && this.roots.contains(value) ? classKind.rootKind() : classKind.kind();
    }

    private static ClassKind classify(Class<?> type) {
        boolean jdk = isJdkClass(type);
        return new ClassKind(type, kindOfClass(type, jdk, false), kindOfClass(type, jdk, true), jdk);
    }

    // - The kind of the objects of a class: arrays, atomic values, values, objects the roots only use (another entity -
    // - than a root among them), objects of other classes outside the JDK, and the collections and maps of the JDK. -
    // - Any other class of the JDK cannot be saved -
    private static Kind kindOfClass(Class<?> type, boolean jdk, boolean root) {
        if (type.isArray()) {
            return Kind.ARRAY;
        }
        if (AtomicLong.class.isAssignableFrom(type) || AtomicInteger.class.isAssignableFrom(type) || AtomicBoolean.class.isAssignableFrom(type)
                || AtomicReference.class.isAssignableFrom(type)) {
            return Kind.ATOMIC;
        }
        if (type.isHidden() || type.isSynthetic() || isSubclassOfAny(type, VALUE_TYPES)
                || BlockPos.class.isAssignableFrom(type) && !BlockPos.MutableBlockPos.class.isAssignableFrom(type)
                || DataComponentMap.class.isAssignableFrom(type) && !PatchedDataComponentMap.class.isAssignableFrom(type)
                || startsWithAny(type.getName(), IMMUTABLE_JDK_COLLECTIONS)) {
            return Kind.VALUE;
        }
        if (Entity.class.isAssignableFrom(type) && !root || isSubclassOfAny(type, SHARED_TYPES) || startsWithAny(type.getName(), SHARED_PACKAGES)) {
            return Kind.SHARED;
        }
        if (!jdk) {
            return Kind.OBJECT;
        }
        if (List.class.isAssignableFrom(type)) {
            return Kind.LIST;
        }
        if (Collection.class.isAssignableFrom(type)) {
            return Kind.COLLECTION;
        }
        if (Map.class.isAssignableFrom(type)) {
            return Kind.MAP;
        }
        return Kind.UNSUPPORTED;
    }

    // - The instance fields of a class and its superclasses, and what a snapshot does with each (see FieldUse). Of the -
    // - JDK only the abstract collections may be superclasses: their only fields count modifications for failing -
    // - iterators and cache views -
    private static ClassLayout layoutOf(Class<?> type) throws SnapshotException {
        ClassLayout cached = LAYOUTS.get(type);
        if (cached != null) {
            return cached;
        }
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class && current != Record.class; current = current.getSuperclass()) {
            if (STATELESS_JDK_SUPERCLASSES.contains(current)) {
                continue;
            }
            if (isJdkClass(current)) {
                throw new SnapshotException("cannot save the fields " + type.getName() + " inherits from " + current.getName());
            }
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (InaccessibleObjectException exception) {
                    throw new SnapshotException("cannot access " + field + ": " + exception.getMessage());
                }
                fields.add(field);
            }
        }
        FieldUse[] uses = new FieldUse[fields.size()];
        Kind[] declaredKinds = new Kind[fields.size()];
        List<Integer> restored = new ArrayList<>();
        for (int index = 0; index < uses.length; index++) {
            Field field = fields.get(index);
            Kind declaredKind = field.getType().isPrimitive() ? null : declaredKind(field.getType());
            uses[index] = useOf(field, declaredKind);
            declaredKinds[index] = declaredKind;
            if (!Modifier.isFinal(field.getModifiers())) {
                restored.add(index);
            }
        }
        ClassLayout layout = new ClassLayout(fields.toArray(Field[]::new), uses, declaredKinds, new ClassKind[fields.size()],
                restored.stream().mapToInt(Integer::intValue).toArray());
        LAYOUTS.put(type, layout);
        return layout;
    }

    // - The kind every value of a field with this declared type has, or null when it depends on the value: any array -
    // - is an array, and a final class other than an entity is the class of every value. An entity's kind depends on -
    // - whether it is a root -
    private static @Nullable Kind declaredKind(Class<?> declared) {
        if (declared.isArray()) {
            return Kind.ARRAY;
        }
        if (Modifier.isFinal(declared.getModifiers()) && !Entity.class.isAssignableFrom(declared)) {
            return CLASS_KINDS.get(declared).kind();
        }
        return null;
    }

    // - A field holding a primitive, or by its declared type only values or objects the roots only use, owns nothing: -
    // - it is read but not followed, and when it is final as well it cannot change and is not even read. A subclass of -
    // - one of the value or shared types is one of them too, while any other field may hold something the roots own -
    private static FieldUse useOf(Field field, @Nullable Kind declaredKind) {
        Class<?> declared = field.getType();
        if (declared.isPrimitive() || declaredKind == Kind.VALUE || declaredKind == Kind.SHARED
                || isSubclassOfAny(declared, VALUE_TYPES) || isSubclassOfAny(declared, SHARED_TYPES)) {
            return Modifier.isFinal(field.getModifiers()) ? FieldUse.SKIP : FieldUse.READ;
        }
        return FieldUse.FOLLOW;
    }

    private static boolean isJdkClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("com.sun.");
    }

    private static boolean isSubclassOfAny(Class<?> type, List<Class<?>> supertypes) {
        for (Class<?> supertype : supertypes) {
            if (supertype.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // - The chain of fields and elements that led from a root to this object -
    private String pathOf(Object object) {
        List<String> steps = new ArrayList<>();
        Object current = object;
        while (current != null) {
            Origin origin = this.origins.get(current);
            if (origin == null) {
                steps.add(current.getClass().getName());
                break;
            }
            steps.add(origin.describe(current));
            current = origin.owner();
        }
        Collections.reverse(steps);
        return String.join(".", steps);
    }

    private enum Kind {
        VALUE, SHARED, OBJECT, ARRAY, LIST, COLLECTION, MAP, ATOMIC, UNSUPPORTED
    }

    // - A class, the kind of its objects, the kind of one that is a root (they differ for entities only), and whether -
    // - the class belongs to the JDK -
    private record ClassKind(Class<?> type, Kind kind, Kind rootKind, boolean jdk) {
    }

    // - What a snapshot does with a field: nothing, read its value, or read it and follow it to what it owns -
    private enum FieldUse {
        SKIP, READ, FOLLOW
    }

    // - The fields of a class, their uses, the kind their declared types decide (null where the value decides it), the -
    // - class each field held last (see kindOfFieldValue), and the indexes of the fields a restore sets, those that are -
    // - not final -
    private record ClassLayout(Field[] fields, FieldUse[] uses, @Nullable Kind[] declaredKinds, @Nullable ClassKind[] seenClasses, int[] restored) {
    }

    // - An object waiting to be saved, with its kind -
    private record Queued(Object object, Kind kind) {
    }

    // - How an object was reached: as a root, through a field of its owner, or at an index of its owner: an element of -
    // - an array or a list, a member of another collection, or a key or the value of the key at that index of a map -
    private enum Step {
        ROOT, FIELD, ELEMENT, MEMBER, KEY, VALUE
    }

    // - The origin of a queued object; its name is only put together when a report needs it -
    private record Origin(@Nullable Object owner, Step step, @Nullable Field field, int index) {

        String describe(Object object) {
            return switch (this.step) {
                case ROOT -> object.getClass().getSimpleName();
                case FIELD -> Objects.requireNonNull(this.field, "a field step names its field").getName();
                case ELEMENT -> "[" + this.index + "]";
                case MEMBER -> "{" + this.index + "}";
                case KEY -> "key " + this.index;
                case VALUE -> "value " + this.index;
            };
        }
    }

    // - The saved state of one object -
    private sealed interface SavedState
            permits FieldState, ArrayState, ListState, CollectionState, MapState, AtomicState, AtomicReferenceState {

        void restore() throws SnapshotException;

        // - The saved values in a fixed order, and the name of each for a report -
        Object[] values();

        String nameOf(int index);
    }

    // - A field the layout skips keeps null here, in both snapshots a comparison looks at -
    private record FieldState(Object target, ClassLayout layout, Object[] values) implements SavedState {

        @Override
        public void restore() throws SnapshotException {
            Field[] fields = this.layout.fields();
            for (int index : this.layout.restored()) {
                try {
                    fields[index].set(this.target, this.values[index]);
                } catch (IllegalAccessException exception) {
                    throw new SnapshotException("cannot restore " + fields[index] + ": " + exception);
                }
            }
        }

        @Override
        public String nameOf(int index) {
            return this.layout.fields()[index].getName();
        }
    }

    private record ArrayState(Object target, Object copy) implements SavedState {

        @Override
        public void restore() {
            System.arraycopy(this.copy, 0, this.target, 0, Array.getLength(this.copy));
        }

        @Override
        public Object[] values() {
            int length = Array.getLength(this.copy);
            Object[] values = new Object[length];
            for (int index = 0; index < length; index++) {
                values[index] = Array.get(this.copy, index);
            }
            return values;
        }

        @Override
        public String nameOf(int index) {
            return "[" + index + "]";
        }
    }

    // - A list keeps its size where it can: a fixed size list (a NonNullList made with a size) only takes set -
    private record ListState<E>(List<E> target, List<E> elements) implements SavedState {

        @Override
        public void restore() throws SnapshotException {
            try {
                if (this.target.size() == this.elements.size()) {
                    for (int index = 0; index < this.elements.size(); index++) {
                        this.target.set(index, this.elements.get(index));
                    }
                } else {
                    this.target.clear();
                    this.target.addAll(this.elements);
                }
            } catch (UnsupportedOperationException exception) {
                throw new SnapshotException("cannot restore the " + this.target.getClass().getName() + ": " + exception);
            }
        }

        @Override
        public Object[] values() {
            return this.elements.toArray();
        }

        @Override
        public String nameOf(int index) {
            return "[" + index + "]";
        }
    }

    private record CollectionState<E>(Collection<E> target, List<E> elements) implements SavedState {

        @Override
        public void restore() throws SnapshotException {
            try {
                this.target.clear();
                this.target.addAll(this.elements);
            } catch (UnsupportedOperationException exception) {
                throw new SnapshotException("cannot restore the " + this.target.getClass().getName() + ": " + exception);
            }
        }

        @Override
        public Object[] values() {
            return this.elements.toArray();
        }

        @Override
        public String nameOf(int index) {
            return "element " + index;
        }
    }

    private record MapState<K, V>(Map<K, V> target, List<K> keys, List<V> mapValues) implements SavedState {

        @Override
        public void restore() throws SnapshotException {
            try {
                this.target.clear();
                for (int index = 0; index < this.keys.size(); index++) {
                    this.target.put(this.keys.get(index), this.mapValues.get(index));
                }
            } catch (UnsupportedOperationException exception) {
                throw new SnapshotException("cannot restore the " + this.target.getClass().getName() + ": " + exception);
            }
        }

        // - Keys and values alternate -
        @Override
        public Object[] values() {
            Object[] entries = new Object[this.keys.size() * 2];
            for (int index = 0; index < this.keys.size(); index++) {
                entries[index * 2] = this.keys.get(index);
                entries[index * 2 + 1] = this.mapValues.get(index);
            }
            return entries;
        }

        @Override
        public String nameOf(int index) {
            return (index % 2 == 0 ? "key " : "value ") + index / 2;
        }
    }

    // - An AtomicLong, AtomicInteger or AtomicBoolean with its value -
    private record AtomicState(Object target, Object value) implements SavedState {

        @Override
        public void restore() {
            switch (this.target) {
                case AtomicLong atomicLong -> atomicLong.set((Long) this.value);
                case AtomicInteger atomicInteger -> atomicInteger.set((Integer) this.value);
                case AtomicBoolean atomicBoolean -> atomicBoolean.set((Boolean) this.value);
                default -> throw new IllegalStateException("not an atomic value: " + this.target.getClass().getName());
            }
        }

        @Override
        public Object[] values() {
            return new Object[] {this.value};
        }

        @Override
        public String nameOf(int index) {
            return "value";
        }
    }

    private record AtomicReferenceState<T>(AtomicReference<T> target, @Nullable T value) implements SavedState {

        @Override
        public void restore() {
            this.target.set(this.value);
        }

        @Override
        public Object[] values() {
            return new Object[] {this.value};
        }

        @Override
        public String nameOf(int index) {
            return "value";
        }
    }

    // - A snapshot could not save or restore everything the roots own -
    static final class SnapshotException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        SnapshotException(String message) {
            super(message);
        }
    }
}
