package arc.assets.loaders;

import arc.assets.*;
import arc.assets.loaders.TextureLoader.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData.*;
import arc.struct.*;
import arc.util.*;

import java.util.concurrent.*;

/**
 * {@link AssetLoader} to load {@link TextureAtlas} instances. Passing a {@link TextureAtlasParameter} to
 * {@link AssetManager#load(String, Class, AssetLoaderParameters)} allows to specify whether the atlas regions should be flipped
 * on the y-axis or not.
 * @author mzechner
 */
public class TextureAtlasLoader extends AsynchronousAssetLoader<TextureAtlas, TextureAtlasLoader.TextureAtlasParameter>{
    TextureAtlasData data;
    ExecutorCompletionService<TextureLoader> pool;
    int numTasks;

    public TextureAtlasLoader(FileHandleResolver resolver){
        super(resolver);
    }

    @Override
    public void loadAsync(AssetManager manager, String fileName, Fi atlasFile, TextureAtlasParameter parameter){
        Fi imgDir = atlasFile.parent();
        data = new TextureAtlasData(atlasFile, imgDir, parameter != null && parameter.flip);

        ExecutorService exec = null; // Used so we can shut it down in this method
        Seq<AssetDescriptor> dependencies = new Seq<>();
        numTasks = 0;
        for(AtlasPage page : data.getPages()){
            String pageFileName = page.textureFile.path().replaceAll("\\\\", "/");
            if(!manager.isLoaded(pageFileName)){
                TextureParameter params = new TextureParameter();
                params.genMipMaps = page.useMipMaps;
                params.minFilter = page.minFilter;
                params.magFilter = page.magFilter;
                if(manager.getLoader(Texture.class, pageFileName).getClass() == TextureLoader.class){ // We cannot trust whatever subclass a mod may use, we should fall back to vanilla behavior
                    if(pool == null) pool = new ExecutorCompletionService(exec = Threads.executor("Texture Atlas Loader"));
                    // A single TextureLoader instance cannot be used by multiple threads at once, create a new one for every page to be safe
                    TextureLoader pageLoader = new TextureLoader(manager.getFileHandleResolver());
                    numTasks++;
                    pool.submit(() -> pageLoader.loadAsync(manager, pageFileName, page.textureFile, params), pageLoader);
                }else{ // Add dependencies so that vanilla behavior is used instead
                    dependencies.add(new AssetDescriptor(page.textureFile, Texture.class, params));
                }
            }
        }
        if(dependencies.any()) Reflect.invoke(manager, "injectDependencies", new Object[]{fileName, dependencies}, String.class, Seq.class); // Emulate vanilla behavior of getDependencies.
        if(exec != null) exec.shutdown(); // shut down the executor but do not wait for it
    }

    @Override
    public TextureAtlas loadSync(AssetManager manager, String fileName, Fi atlasFile, TextureAtlasParameter parameter){
        if(pool != null){
            long await = Time.nanos();
            try {
                for(int i = 0; i < numTasks; i++){ // Funnily enough, the longest part of the atlas loading process is just waiting for the first page to finish loadAsync
                    TextureLoader pageLoader = pool.take().get();
                    AtlasPage page = data.getPages().find(p -> p.textureFile.path().replaceAll("\\\\", "/").equals(pageLoader.info.filename));
                    TextureParameter params = new TextureParameter();
                    params.genMipMaps = page.useMipMaps;
                    params.minFilter = page.minFilter;
                    params.magFilter = page.magFilter;
                    // Run the sync portion
                    page.texture = pageLoader.loadSync(manager, pageLoader.info.filename, page.textureFile, params);
                }
            }catch(InterruptedException | ExecutionException e){
                throw new ArcRuntimeException(e);
            }
            pool = null;
            Log.debug("Awaited atlas pool for: @ms", Time.millisSinceNanos(await));
        }

        // If a mod has caused the vanilla behavior fallback, the page textures won't have been set by the block above
        for(AtlasPage page : data.getPages()){
            if(page.texture != null) continue; // This page was already set by the block above
            page.texture = manager.get(page.textureFile.path(), Texture.class);
        }

        TextureAtlas atlas = new TextureAtlas(data);
        data = null;
        return atlas;
    }

    @Override
    public Seq<AssetDescriptor> getDependencies(String fileName, Fi atlasFile, TextureAtlasParameter parameter){
        return null; // We will inject the dependencies if they're needed later
    }

    public static class TextureAtlasParameter extends AssetLoaderParameters<TextureAtlas>{
        /** whether to flip the texture atlas vertically **/
        public boolean flip = false;

        public TextureAtlasParameter(){
        }

        public TextureAtlasParameter(boolean flip){
            this.flip = flip;
        }

        public TextureAtlasParameter(LoadedCallback loadedCallback){
            super(loadedCallback);
        }
    }
}
