package arc.audio;

import arc.files.*;
import arc.util.*;

public interface DownloadableAudio {

    void load(Fi file);

    default ArcRuntimeException loadDirectly(Fi dest){
        load(dest);
        return null;
    }
}
