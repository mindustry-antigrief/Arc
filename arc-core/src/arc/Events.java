package arc;

import arc.func.*;
import arc.struct.*;
import arc.util.*;

import java.util.*;

/** Simple global event listener system. */
@SuppressWarnings("unchecked")
public class Events{
    private static int lastId = -1;
    private static int index; // Used for iteration, based on EntityGroup
    private static class Handler<T>{ // FINISHME: Pool these maybe? I doubt it's needed though
        final int id = ++lastId;
        final Cons<T> cons;

        Handler(Cons<T> cons){
            this.cons = cons;
        }
    }

    private static final ObjectMap<Object, Seq<Handler<?>>> events = new ObjectMap<>();

    /** Handle an event by class. */
    public static <T> void on(Class<T> type, Cons<T> listener){
        events.get(type, () -> new Seq<>(Handler.class)).add(new Handler<>(listener));
    }

    /** Handle an event by class. Returns an id */
    public static <T> int onid(Class<T> type, Cons<T> listener){
        on(type, listener);
        return lastId;
    }

    /** Handle an event by enum trigger. */
    public static void run(Object type, Runnable listener){
        events.get(type, () -> new Seq<>(Handler.class)).add(new Handler<>(e -> listener.run()));
    }

    /** Handle an event by enum trigger. Returns an id */
    public static int runid(Object type, Runnable listener){
        run(type, listener);
        return lastId;
    }

    /** Only use this method if you have the reference to the exact listener object that was used.
     * Doesn't work with listeners added through {@link #run(Object, Runnable)}, use {@link #remove(Class, int)} for that. */
    public static <T> boolean remove(Class<T> type, Cons<T> listener){
        return events.get(type, () -> new Seq<>(Cons.class)).remove(h -> h.cons == listener);
    }

    /** Only use this method if you have the reference to the exact listener object that was used. */
    public static <T> boolean remove(Class<T> type, int id){
        Seq<Handler<?>> listeners = events.get(type, () -> new Seq<>(Handler.class));
        if(listeners.isEmpty() || listeners.first().id > id || listeners.get(listeners.size - 1).id < id) return false; // id is less than smallest or larger than max id of this type
        int idx = Arrays.binarySearch(listeners.items, 0, listeners.size, null, Structs.comparingInt(h -> h != null ? h.id : id));
        if(idx < 0) return false; // The event ID wasn't found
        listeners.remove(idx);
        if(index >= idx) index--; // Decrement the index counter so that iteration works correctly

        return true;
    }

    /** Fires an enum trigger. */
    public static <T extends Enum<T>> void fire(Enum<T> type){
        Seq<Handler<?>> listeners = events.get(type);

        if(listeners != null){
            Handler[] items = listeners.items;
            for(index = 0; index < listeners.size; index++){
                items[index].cons.get(type);
            }
        }
    }

    /** Fires a non-enum event by class. */
    public static <T> void fire(T type){
        fire(type.getClass(), type);
    }

    public static <T> void fire(Class<?> ctype, T type){
        Seq<Handler<?>> listeners = events.get(ctype);

        if(listeners != null){
            Handler[] items = listeners.items;
            for(index = 0; index < listeners.size; index++){
                items[index].cons.get(type);
            }
        }
    }

    /** Don't do this. */
    public static void clear(){
        events.clear();
    }
}