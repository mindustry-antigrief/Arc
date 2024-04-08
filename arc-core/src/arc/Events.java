package arc;

import arc.func.*;
import arc.struct.*;

import java.util.*;

/** Simple global event listener system. */
@SuppressWarnings("unchecked")
public class Events{
    private static int lastId = -1;
    private static class Handler<T>{ // FINISHME: Pool these maybe? I doubt it's needed though
        private int id = ++lastId;
        private final Cons<T> cons;

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
        Seq<Handler<?>> listeners = events.get(type);
        if(listeners == null || listeners.isEmpty() || id < 0 || id > lastId) return false;
        Handler<?> found = listeners.find(h -> h.id == id);
        if(found == null) return false;

        found.id = -1; // Mark for deletion FINISHME: Purge the marked events every so often as they will currently sit around in memory forever if the event is never fired after removal
        return true;
    }

    /** Fires an enum trigger. */
    public static <T extends Enum<T>> void fire(Enum<T> type){
        Seq<Handler<?>> listeners = events.get(type);

        if(listeners != null){
            Handler[] items = listeners.items;
            for(int i = 0; i < listeners.size; i++){
                items[i].cons.get(type);
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
            Iterator<Handler<T>> it = listeners.<Handler<T>>as().iterator();
            while(it.hasNext()){
                Handler<T> listener = it.next();
                if(listener.id == -1){
                    it.remove();
                    continue;
                }
                listener.cons.get(type);
            }
        }
    }

    /** Don't do this. */
    public static void clear(){
        events.clear();
    }
}