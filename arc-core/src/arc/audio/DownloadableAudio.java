package arc.audio;

import arc.files.*;

public interface DownloadableAudio {

    void load(Fi file);

    default void load(Fi file, boolean alreadyRenamed){
        load(file);
    }
}
