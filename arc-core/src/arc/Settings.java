package arc;

import arc.files.*;
import arc.func.*;
import arc.struct.*;
import arc.struct.ObjectMap.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.serialization.*;

import java.io.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

import static arc.Core.*;

public class Settings{
    protected final static byte typeBool = 0, typeInt = 1, typeLong = 2, typeFloat = 3, typeString = 4, typeBinary = 5;
    protected final static int maxBackups = 10;

    //general state data
    protected Fi dataDirectory;
    protected String appName = "app";
    protected ObjectMap<String, Object> defaults = new ObjectMap<>();
    protected HashMap<String, Object> values = new HashMap<>();
    protected HashMap<String, Object> overrideValues = new HashMap<>();
    protected boolean modified;
    protected Cons<Throwable> errorHandler;
    protected boolean hasErrored;
    protected boolean shouldAutosave = true;
    protected boolean loaded = false;
    protected ExecutorService executor = Threads.executor("Settings Backup", 1);
    private static final boolean debug = OS.hasProp("settingsDebug");

    //IO utility objects
    protected ByteArrayOutputStream byteStream = new ByteArrayOutputStream(32);
    protected ReusableByteInStream byteInputStream = new ReusableByteInStream();
    protected UBJsonReader ureader = new UBJsonReader();
    protected Json json = new Json();

    public void setJson(Json json){
        this.json = json;
    }

    public String getAppName(){
        return appName;
    }

    public void setAppName(String name){
        appName = name;
    }

    /**Sets the error handler function.
     * This function gets called when {@link #forceSave} or {@link #load} fails. This can occur most often on browsers,
     * where extensions can block writing to local storage.*/
    public void setErrorHandler(Cons<Throwable> handler){
        errorHandler = handler;
    }

    /** Set whether the data should autosave immediately upon changing a value.
     * Default value: true. */
    public void setAutosave(boolean autosave){
        this.shouldAutosave = autosave;
    }

    public boolean modified(){
        return modified;
    }

    /** Loads all values and keybinds. */
    public synchronized void load(){
        if(debug) Log.warn("Settings debug enabled!"); // Can't use debug logging here as it's likely not enabled yet
        try{
            loadValues();
            keybinds.load();
        }catch(Throwable error){
            writeLog("Error in load: " + Strings.getStackTrace(error));
            if(errorHandler != null){
                if(!hasErrored) errorHandler.get(error);
            }else{
                throw error;
            }
            hasErrored = true;
        }
        //if loading failed, it still counts
        loaded = true;
    }

    /** Saves all values and keybinds. */
    public synchronized void forceSave(){
        //never loaded, nothing to save
        if(!loaded) return;
        try{
            keybinds.save();
            saveValues();
        }catch(Throwable error){
            writeLog("Error in forceSave to " + getSettingsFile() + ":\n" + Strings.getStackTrace(error));
            if(errorHandler != null){
                if(!hasErrored) errorHandler.get(error);
            }else{
                throw error;
            }
            hasErrored = true;
        }
        modified = false;
    }

    /** Manually save, if the settings have been loaded at some point. */
    public synchronized void manualSave(){
        if(loaded){
            forceSave();
        }
    }

    /** Saves if any modifications were done. */
    public synchronized void autosave(){
        if(modified && shouldAutosave){
            forceSave();
            modified = false;
        }
    }

    /** Loads a settings file into {@link #values} using the specified appName. */
    public synchronized void loadValues(){
        //don't load settings files if neither of them exist
        if(!getSettingsFile().exists() && !getBackupSettingsFile().exists()){
            writeLog("No settings files found: " + getSettingsFile().absolutePath() + " and " + getBackupSettingsFile().absolutePath());
            return;
        }

        try{
            loadValues(getSettingsFile());
            writeLog("Loaded " + values.size() + " values");
            if(OS.hasProp("settingsOverride")) {
                loadOverrideValues();
                if(!overrideValues.isEmpty()){
                    Log.debug("Loaded @ override values", overrideValues.size());
                }
            } else {
                Log.debug("No settings override. ");
            }

            //back up the save file, as the values have now been loaded successfully
            getSettingsFile().copyTo(getBackupSettingsFile());
            writeLog("Backed up " + getSettingsFile() + " to " + getBackupSettingsFile() + " (" + getBackupSettingsFile().length() + " bytes)");
        }catch(Throwable e){
            Log.err("Failed to load base settings file, attempting to load backup.", e);
            writeLog("Failed to load base file " + getSettingsFile() + ":\n" + Strings.getStackTrace(e));

            Seq<Fi> attempts = getBackupFolder().seq().add(getBackupSettingsFile());
            //sort with latest modified file first
            attempts.sort(Structs.comparingLong(f -> -f.lastModified()));

            for(Fi attempt : attempts){
                try{
                    writeLog("Attempting to load backup file: '" + attempt + "'. Length: " + attempt.length());

                    loadValues(attempt);
                    attempt.copyTo(getSettingsFile());

                    Log.info("Loaded backup settings file successfully!");
                    writeLog("| Loaded backup settings file after load failure. New settings file length: " + getSettingsFile().length());

                    //break out of loop, we're done here
                    return;
                }catch(Throwable e3){
                    writeLog("| Failed to load backup file " + attempts + ":\n" + Strings.getStackTrace(e3));
                    Log.err("Failed to load backup settings file.", e3);
                }
            }
        }
    }

    public synchronized void loadValues(Fi file) throws IOException{
        try(DataInputStream stream = new DataInputStream(file.read(8192))){
            int amount = stream.readInt();
            //current theory: when corruptions happen, the only things written to the stream are a bunch of zeroes
            //try to anticipate this case and throw an exception when 0 values are written
            if(amount <= 0) throw new IOException("0 values are not allowed.");
            for(int i = 0; i < amount; i++){
                String key = stream.readUTF();
                byte type = stream.readByte();

                switch(type){
                    case typeBool:
                        values.put(key, stream.readBoolean());
                        break;
                    case typeInt:
                        values.put(key, stream.readInt());
                        break;
                    case typeLong:
                        values.put(key, stream.readLong());
                        break;
                    case typeFloat:
                        values.put(key, stream.readFloat());
                        break;
                    case typeString:
                        values.put(key, stream.readUTF());
                        break;
                    case typeBinary:
                        int length = stream.readInt();
                        byte[] bytes = new byte[length];
                        stream.read(bytes);
                        values.put(key, bytes);
                        break;
                    default:
                        throw new IOException("Unknown key type: " + type);
                }
            }
            //make sure all data was read - this helps with potential corruption
            int end = stream.read();
            if(end != -1){
                throw new IOException("Trailing settings data; expected EOF, but got: " + end);
            }
        }
    }

    public synchronized void loadOverrideValues() throws IOException{
        String[] props = OS.prop("settingsOverride").split(",");
        for(String prop : props) {
            if(OS.hasProp(prop)) {
                overrideValues.put(prop, OS.prop(prop));
            }
            Log.debug("Set setting @ to @", prop, OS.prop(prop));
        }
    }

    /** Saves all entries from {@link #values} into the correct location. */
    public synchronized void saveValues(){
        Fi file = getSettingsFile();

        try(DataOutputStream stream = new DataOutputStream(file.write(false, 8192))){
            stream.writeInt(values.size());

            for(Map.Entry<String, Object> entry : values.entrySet()){
                stream.writeUTF(entry.getKey());

                Object value = entry.getValue();

                if(value instanceof Boolean){
                    stream.writeByte(typeBool);
                    stream.writeBoolean((Boolean)value);
                }else if(value instanceof Integer){
                    stream.writeByte(typeInt);
                    stream.writeInt((Integer)value);
                }else if(value instanceof Long){
                    stream.writeByte(typeLong);
                    stream.writeLong((Long)value);
                }else if(value instanceof Float){
                    stream.writeByte(typeFloat);
                    stream.writeFloat((Float)value);
                }else if(value instanceof String){
                    stream.writeByte(typeString);
                    stream.writeUTF((String)value);
                }else if(value instanceof byte[]){
                    stream.writeByte(typeBinary);
                    stream.writeInt(((byte[])value).length);
                    stream.write((byte[])value);
                }
            }

        }catch(Throwable e){
            //file is now corrupt, delete it
            file.delete();
            throw new RuntimeException("Error writing preferences: " + file, e);
        }

        writeLog("Saving " + values.size() + " values; " + file.length() + " bytes");

        executor.submit(() -> {
            //make sure two backups can't happen at once.
            synchronized(this){
                Fi backupFolder = getBackupFolder();

                Seq<Fi> previous = backupFolder.seq();
                //make sure first file is most recent, last is oldest
                previous.sort(Structs.comparingLong(f -> -f.lastModified()));

                //create new entry in the backup folder
                file.copyTo(backupFolder.child(System.currentTimeMillis() + ".bin"));

                //delete older backups if they exceed the max backup count
                while(previous.size >= maxBackups){
                    previous.pop().delete();
                }
            }
        });
    }

    /** Returns the file used for writing settings to. Not available on all platforms! */
    public Fi getSettingsFile(){
        return getDataDirectory().child("settings.bin");
    }

    public Fi getBackupFolder(){
        return getDataDirectory().child("settings_backups");
    }

    public Fi getBackupSettingsFile(){
        return getDataDirectory().child("settings_backup.bin");
    }

    /** Returns the directory where all settings and data is placed. */
    public Fi getDataDirectory(){
        return dataDirectory == null ? Core.files.absolute(OS.getAppDataDirectoryString(appName)) : dataDirectory;
    }

    /** Sets the settings file where everything is written to. */
    public void setDataDirectory(Fi file){
        this.dataDirectory = file;
    }

    /**
     * Set up a list of defaults values.
     * Format: name1, default1, name2, default2, etc
     */
    public synchronized void defaults(Object... objects){
        for(int i = 0; i < objects.length; i += 2){
            defaults.put((String)objects[i], objects[i + 1]);
        }
    }

    /** Clears all preference values. */
    public synchronized void clear(){
        values.clear();
    }

    public synchronized Object getDefault(String name){
        return defaults.get(name);
    }

    public synchronized boolean has(String name){
        if(!loaded && debug) Log.debug("Call to Settings.has before settings loaded:\n@", Threads.getTrace());
        return values.containsKey(name) || overrideValues.containsKey(name);
    }

    public synchronized Object get(String name, Object defaultValue){
        if(!loaded && debug) Log.debug("Call to Settings.get before settings loaded:\n@", Threads.getTrace());
        return overrideValues.getOrDefault(name, values.getOrDefault(name, defaultValue)); // FINISHME: We could optimize this to skip the first getOrDefault if we know overrideValues is always empty
    }

    public boolean isModified(){
        return modified;
    }

    public synchronized void putJson(String name, Object value){
        putJson(name, null, value);
    }

    public synchronized void putJson(String name, Class<?> elementType, Object value){
        byteStream.reset();

        json.setWriter(new UBJsonWriter(byteStream));
        json.writeValue(value, value == null ? null : value.getClass(), elementType);

        put(name, byteStream.toByteArray());

        modified = true;
    }

    public synchronized <T> T getJson(String name, Class<T> type, Class elementType, Prov<T> def){
        try{
            if(!has(name)) return def.get();
            byteInputStream.setBytes(getBytes(name));
            return json.readValue(type, elementType, ureader.parse(byteInputStream));
        }catch(Throwable e){
            writeLog("Failed to write JSON key=" + name + " type=" + type + ":\n" + Strings.getStackTrace(e));
            return def.get();
        }
    }

    public <T> T getJson(String name, Class<T> type, Prov<T> def){
        return getJson(name, type, null, def);
    }

    public float getFloat(String name, float def){
        return (float)get(name, def);
    }

    public long getLong(String name, long def){
        return (long)get(name, def);
    }

    /** Do not use this it has a bad return type but needs to be kept for compat reasons */
    public Long getLong(String name){
        return getLong(name, 0);
    }

    public int getInt(String name, int def){
        return (int)get(name, def);
    }

    public boolean getBool(String name, boolean def){
        return (boolean)get(name, def);
    }

    public byte[] getBytes(String name, byte[] def){
        return (byte[])get(name, def);
    }

    public String getString(String name, String def){
        return (String)get(name, def);
    }

    public float getFloat(String name){
        return getFloat(name, (float)defaults.get(name, 0f));
    }

    public int getInt(String name){
        return getInt(name, (int)defaults.get(name, 0));
    }

    public boolean getBool(String name){
        return getBool(name, (boolean)defaults.get(name, false));
    }

    /** Runs the specified code once, and never again. */
    public void getBoolOnce(String name, Runnable run){
        if(!getBool(name, false)){
            run.run();
            put(name, true);
        }
    }

    /** Returns true once, and never again. */
    public boolean getBoolOnce(String name){
        boolean val = getBool(name, false);
        put(name, true);
        return val;
    }

    public byte[] getBytes(String name){
        return getBytes(name, (byte[])defaults.get(name));
    }

    public String getString(String name){
        return getString(name, (String)defaults.get(name));
    }

    public void putAll(ObjectMap<String, Object> map){
        for(Entry<String, Object> entry : map.entries()){
            put(entry.key, entry.value);
        }
    }

    /** Stores an object in the preference map. To use this with integers or floats in js: Core.settings.put("name", java.lang.Integer(1)) */
    public synchronized void put(String name, Object object){
        if(object instanceof Float || object instanceof Integer || object instanceof Boolean || object instanceof Long
        || object instanceof String || object instanceof byte[]){
            values.put(name, object);
//            if (object instanceof Boolean && (Boolean) object == getBool(name)) {
//                Log.info("Wrote boolean @ with value @ needlessly", name, object);
//            } else if (object instanceof Integer && (Integer) object == getInt(name)) {
//                Log.info("Wrote integer @ with value @ needlessly", name, object);
//            } else if (object instanceof Long && (Long) object == getLong(name)) {
//                Log.info("Wrote long @ with value @ needlessly", name, object);
//            } else if (object instanceof Float && (Float) object == getFloat(name)) {
//                Log.info("Wrote float @ with value @ needlessly", name, object);
//            } else if (object instanceof String && object.equals(getString(name))) {
//                Log.info("Wrote string @ with value @ needlessly", name, object);
//            } else if (object instanceof byte[] && object == getBytes(name)) {
//                Log.info("Wrote byte @ with value @ needlessly", name, object);
//            }
            modified = true;
        }else{
            throw new IllegalArgumentException("Invalid object stored: " + (object == null ? null : object.getClass()) + ".");
        }
    }

    public synchronized void remove(String name){
        values.remove(name);
        modified = true;
    }

    public synchronized Iterable<String> keys(){
        return values.keySet();
    }

    public synchronized int keySize(){
        return values.size();
    }

    /** Appends to the settings log. Used for diagnosis of the save wipe bug. Never throws an error. */
    void writeLog(String text){
        try{
            Fi log = getDataDirectory().child("settings.log");
            log.writeString("[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "] " + text + "\n", true);
        }catch(Throwable t){
            Log.err("Failed to write settings log", t);
        }
    }
}
