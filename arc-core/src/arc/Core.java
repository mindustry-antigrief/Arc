package arc;

import arc.assets.*;
import arc.audio.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.*;
import arc.util.*;

/**
 * Global references to all of Arc's core modules.
 * */
public class Core{
    public static Application app;
    public static Graphics graphics;
    public static Audio audio;
    public static Input input;
    public static Files files;
    public static Settings settings;
    public static KeyBinds keybinds = new KeyBinds();

    public static I18NBundle bundle = I18NBundle.createEmptyBundle();
    public static Camera camera;
    public static Batch batch;
    public static Scene scene;
    public static AssetManager assets;
    public static TextureAtlas atlas;

    public static GL20 gl;
    public static GL20 gl20;
    public static GL30 gl30;
}
