package arc.input;

import arc.struct.*;
import arc.util.*;
import java.util.*;

import static arc.Core.*;

public class KeyBind{
    public static final Seq<KeyBind> all = new Seq<>();

    public final String name;
    public final KeybindValue defaultValue;
    public final @Nullable String category;
    public Axis value;

    /**
     * Registers a new key binding.
     * @param category Name of the category in the list of keybinds. This should be your mod name. In a bundle, it uses the key `category.{name}.name`.
     * @param name Unique name of the keybind.
     * @param defaultValue The default value for this key; can be an Axis or a KeyCode.
     */
    public static KeyBind add(String name, KeybindValue defaultValue, String category){
        return new KeyBind(name, defaultValue, category);
    }

    /**
     * Registers a new key binding without a category. Not for use in mods. Use the constructor with a category.
     * @param name Unique name of the keybind.
     * @param defaultValue The default value for this key; can be an Axis or a KeyCode.
     */
    public static KeyBind add(String name, KeybindValue defaultValue){
        return new KeyBind(name, defaultValue, null);
    }

    public static void resetAll(){
        for(KeyBind def : all){
            def.resetToDefault();
        }
    }

    protected KeyBind(String name, KeybindValue defaultValue, String category){
        this.name = name;
        this.defaultValue = defaultValue;
        this.category = category;
        this.value = defaultValue instanceof Axis ? (Axis)defaultValue : new Axis((KeyCode)defaultValue);

        all.add(this);

        load();
    }

    /** Saves this keybind to Settings. Call after modifying the value. */
    public void save(){
        String name = settingsKey();
        settings.put(name + "-single", value.key != null);

        if(value.key != null){
            settings.put(name + "-key", value.key.ordinal());
        }else{
            settings.put(name + "-min", value.min.ordinal());
            settings.put(name + "-max", value.max.ordinal());
        }
        if(value.modifiers.length > 0){
            byte[] modifierIDs = new byte[value.modifiers.length];
            for(int i = 0; i < value.modifiers.length; i ++){
                modifierIDs[i] = (byte)value.modifiers[i].ordinal();
            }
            settings.put(name + "-modifiers", modifierIDs);
        } else {
            settings.remove(name + "-modifiers");
        }
    }

    /** Loads this keybind from settings. Calling this manually should not be necessary in most cases. */
    public void load(){
        if(settings == null) return; //headless usage

        Axis loaded;
        String name = settingsKey();
        if(settings.getBool(name + "-single", true)){
            KeyCode key = KeyCode.byOrdinal(settings.getInt(name + "-key", KeyCode.anyKey.ordinal()));
            loaded = key == KeyCode.anyKey ? null : new Axis(key);
        }else{
            KeyCode min = KeyCode.byOrdinal(settings.getInt(name + "-min", KeyCode.anyKey.ordinal()));
            KeyCode max = KeyCode.byOrdinal(settings.getInt(name + "-max", KeyCode.anyKey.ordinal()));
            loaded = min == KeyCode.anyKey || max == KeyCode.anyKey ? null : new Axis(min, max);
        }
        @Nullable byte[] modifierIDs = settings.getBytes(name + "-modifiers", null);
        if(loaded != null && modifierIDs != null){
            loaded.modifiers = new KeyCode[modifierIDs.length];
            for(int i = 0; i < modifierIDs.length; i ++){
                loaded.modifiers[i] = KeyCode.byOrdinal(modifierIDs[i] & 0xFF);
            }
        }

        if(loaded != null){
            value = loaded;
        }
    }

    public void resetToDefault(){
        String name = settingsKey();
        settings.remove(name + "-single");
        settings.remove(name + "-key");
        settings.remove(name + "-min");
        settings.remove(name + "-max");
        settings.remove(name + "-modifiers");

        if(defaultValue instanceof Axis){
            if(((Axis)defaultValue).min == null){
                value = new Axis(((Axis)defaultValue).key);
            }else{
                value = new Axis(((Axis)defaultValue).min, ((Axis)defaultValue).max);
            }
            value.modifiers = ((Axis)defaultValue).modifiers;
        }else{
            value = new Axis((KeyCode)defaultValue);
        }
    }

    public void unset(){
        String name = settingsKey();
        settings.remove(name + "-single");
        settings.remove(name + "-key");
        settings.remove(name + "-min");
        settings.remove(name + "-max");
        settings.remove(name + "-modifiers");
        value = new Axis(KeyCode.unset);
    }

    public boolean isDefault(){
        if(defaultValue instanceof Axis){
            if(((Axis)defaultValue).min == null){
                return ((Axis)defaultValue).key == value.key && Arrays.equals(((Axis)defaultValue).modifiers, value.modifiers);
            }else{
                return ((Axis)defaultValue).max == value.max && ((Axis)defaultValue).min == value.min && Arrays.equals(((Axis)defaultValue).modifiers, value.modifiers);
            }
        }else{
            return defaultValue == value.key && value.modifiers.length == 0;
        }
    }

    public boolean isUnset(){
        return value.key == KeyCode.unset;
    }

    String settingsKey(){
        return "keybind-default-keyboard-" + name;
    }

    /** Represents an Axis or a KeyCode. */
    public interface KeybindValue{}

    public static class Axis implements KeybindValue{
        public @Nullable KeyCode min, max;
        public @Nullable KeyCode key;
        private static final KeyCode[] EMPTY = new KeyCode[0];
        /** Do not modify the array's slots, create a new one instead. */
        public KeyCode[] modifiers = EMPTY;

        /** Cosntructor for axis-type keys only. */
        public Axis(KeyCode key){
            this.key = key;
            this.min = max = null;
        }

        /** Constructor for keyboards/mice, or multiple buttons on a controller. */
        public Axis(KeyCode min, KeyCode max){
            this.min = min;
            this.max = max;
            this.key = null;
        }

        @Override
        public boolean equals(Object o){
            if(this == o) return true;
            if(o == null || getClass() != o.getClass()) return false;

            Axis axis = (Axis)o;
            return min == axis.min && max == axis.max && key == axis.key;
        }

        public void clearModifiers(){
            modifiers = EMPTY;
        }
    }
}
