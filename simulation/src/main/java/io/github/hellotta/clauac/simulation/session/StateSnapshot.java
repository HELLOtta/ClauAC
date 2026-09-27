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
    // - The instance fields of each class, its superclasses' included, that a snapshot saves -
    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();
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
    // - Superclasses of the JDK whose fields do not matter to a snapshot of a subclass (see fieldsOf) -
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

    private final Set<Object> roots;
    private final Map<Object, SavedState> states = new IdentityHashMap<>();
    // - How each saved object was reached, to name it when something goes wrong -
    private final Map<Object, Origin> origins = new IdentityHashMap<>();

    private StateSnapshot(Collection<?> roots) {
        this.roots = Collections.newSetFromMap(new IdentityHashMap<>());
        this.roots.addAll(roots);
    }

    // - Saves everything the roots own -
    static StateSnapshot capture(Collection<?> roots) throws SnapshotException {
        StateSnapshot snapshot = new StateSnapshot(roots);
        Deque<Object> pending = new ArrayDeque<>();
        for (Object root : roots) {
            snapshot.origins.put(root, new Origin(null, root.getClass().getSimpleName()));
            pending.add(root);
        }
        while (!pending.isEmpty()) {
            Object object = pending.poll();
            if (snapshot.states.containsKey(object)) {
                continue;
            }
            if (snapshot.states.size() >= MAX_OBJECTS) {
                throw new SnapshotException("more than " + MAX_OBJECTS + " objects, the last at " + snapshot.pathOf(object));
            }
            snapshot.states.put(object, snapshot.save(object, pending));
        }
        return snapshot;
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
        if (isJdkClass(type) || type.isHidden() || type.isSynthetic() || this.kindOf(earlier) == Kind.SHARED) {
            return false;
        }
        if (!comparing.add(earlier)) {
            return true;
        }
        for (Field field : fieldsOf(type)) {
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

    private SavedState save(Object object, Deque<Object> pending) throws SnapshotException {
        Kind kind = this.kindOf(object);
        return switch (kind) {
            case ARRAY -> this.saveArray(object, pending);
            case LIST -> this.saveList((List<?>) object, pending);
            case COLLECTION -> this.saveCollection((Collection<?>) object, pending);
            case MAP -> this.saveMap((Map<?, ?>) object, pending);
            case ATOMIC -> saveAtomic(object);
            case OBJECT -> this.saveFields(object, pending);
            case VALUE, SHARED -> throw new SnapshotException("a " + kind + " at " + this.pathOf(object) + " was queued to be saved");
        };
    }

    // - Queues a referenced object when the roots own it -
    private void follow(Object owner, String step, @Nullable Object value, Deque<Object> pending) throws SnapshotException {
        if (value == null || this.states.containsKey(value) || this.origins.containsKey(value)) {
            return;
        }
        Kind kind = this.kindOf(value);
        if (kind == Kind.VALUE || kind == Kind.SHARED) {
            return;
        }
        this.origins.put(value, new Origin(owner, step));
        pending.add(value);
    }

    private SavedState saveFields(Object object, Deque<Object> pending) throws SnapshotException {
        List<Field> fields = fieldsOf(object.getClass());
        Object[] values = new Object[fields.size()];
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            Object value;
            try {
                value = field.get(object);
            } catch (IllegalAccessException exception) {
                throw new SnapshotException("cannot read " + field + " at " + this.pathOf(object) + ": " + exception);
            }
            values[index] = value;
            if (!field.getType().isPrimitive()) {
                this.follow(object, field.getName(), value, pending);
            }
        }
        return new FieldState(object, fields, values);
    }

    private SavedState saveArray(Object array, Deque<Object> pending) throws SnapshotException {
        int length = Array.getLength(array);
        Object copy = Array.newInstance(array.getClass().getComponentType(), length);
        System.arraycopy(array, 0, copy, 0, length);
        if (!array.getClass().getComponentType().isPrimitive()) {
            Object[] elements = (Object[]) copy;
            for (int index = 0; index < length; index++) {
                this.follow(array, "[" + index + "]", elements[index], pending);
            }
        }
        return new ArrayState(array, copy);
    }

    // - The collections below are saved with their own element types, so that restoring them needs no unchecked cast -
    private <E> SavedState saveList(List<E> list, Deque<Object> pending) throws SnapshotException {
        List<E> elements = new ArrayList<>(list);
        for (int index = 0; index < elements.size(); index++) {
            this.follow(list, "[" + index + "]", elements.get(index), pending);
        }
        return new ListState<>(list, elements);
    }

    private <E> SavedState saveCollection(Collection<E> collection, Deque<Object> pending) throws SnapshotException {
        List<E> elements = new ArrayList<>(collection);
        for (int index = 0; index < elements.size(); index++) {
            this.follow(collection, "{" + index + "}", elements.get(index), pending);
        }
        return new CollectionState<>(collection, elements);
    }

    private <K, V> SavedState saveMap(Map<K, V> map, Deque<Object> pending) throws SnapshotException {
        int size = map.size();
        List<K> keys = new ArrayList<>(size);
        List<V> values = new ArrayList<>(size);
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (keys.size() == size) {
                throw new SnapshotException("the map at " + this.pathOf(map) + " changed while it was saved");
            }
            this.follow(map, "key " + keys.size(), entry.getKey(), pending);
            this.follow(map, "value of " + entry.getKey(), entry.getValue(), pending);
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

    private Kind kindOf(Object value) throws SnapshotException {
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return Kind.ARRAY;
        }
        if (value instanceof AtomicLong || value instanceof AtomicInteger || value instanceof AtomicBoolean || value instanceof AtomicReference<?>) {
            return Kind.ATOMIC;
        }
        if (type.isHidden() || type.isSynthetic() || isInstanceOfAny(value, VALUE_TYPES)
                || value instanceof BlockPos && !(value instanceof BlockPos.MutableBlockPos)
                || value instanceof DataComponentMap && !(value instanceof PatchedDataComponentMap)
                || startsWithAny(type.getName(), IMMUTABLE_JDK_COLLECTIONS)) {
            return Kind.VALUE;
        }
        if (value instanceof Entity && !this.roots.contains(value) || isInstanceOfAny(value, SHARED_TYPES) || startsWithAny(type.getName(), SHARED_PACKAGES)) {
            return Kind.SHARED;
        }
        if (!isJdkClass(type)) {
            return Kind.OBJECT;
        }
        if (value instanceof List<?>) {
            return Kind.LIST;
        }
        if (value instanceof Collection<?>) {
            return Kind.COLLECTION;
        }
        if (value instanceof Map<?, ?>) {
            return Kind.MAP;
        }
        throw new SnapshotException("cannot save the " + type.getName() + " at " + this.pathOf(value));
    }

    // - The instance fields of a class and its superclasses. Of the JDK only the abstract collections may be -
    // - superclasses: their only fields count modifications for failing iterators and cache views -
    private static List<Field> fieldsOf(Class<?> type) throws SnapshotException {
        List<Field> cached = FIELDS.get(type);
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
        List<Field> saved = List.copyOf(fields);
        FIELDS.put(type, saved);
        return saved;
    }

    private static boolean isJdkClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("com.sun.");
    }

    private static boolean isInstanceOfAny(Object value, List<Class<?>> types) {
        for (Class<?> type : types) {
            if (type.isInstance(value)) {
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
            steps.add(origin.step());
            current = origin.owner();
        }
        Collections.reverse(steps);
        return String.join(".", steps);
    }

    private enum Kind {
        VALUE, SHARED, OBJECT, ARRAY, LIST, COLLECTION, MAP, ATOMIC
    }

    private record Origin(@Nullable Object owner, String step) {
    }

    // - The saved state of one object -
    private sealed interface SavedState
            permits FieldState, ArrayState, ListState, CollectionState, MapState, AtomicState, AtomicReferenceState {

        void restore() throws SnapshotException;

        // - The saved values in a fixed order, and the name of each for a report -
        Object[] values();

        String nameOf(int index);
    }

    private record FieldState(Object target, List<Field> fields, Object[] values) implements SavedState {

        @Override
        public void restore() throws SnapshotException {
            for (int index = 0; index < this.fields.size(); index++) {
                Field field = this.fields.get(index);
                if (Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                try {
                    field.set(this.target, this.values[index]);
                } catch (IllegalAccessException exception) {
                    throw new SnapshotException("cannot restore " + field + ": " + exception);
                }
            }
        }

        @Override
        public String nameOf(int index) {
            return this.fields.get(index).getName();
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
