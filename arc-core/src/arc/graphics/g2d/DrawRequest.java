package arc.graphics.g2d;

import arc.graphics.*;

class DrawRequest{
    TextureRegion region;
    float x, y, z, originX, originY, width, height, rotation, color, mixColor;
    float[] vertices = new float[24];
    Texture texture;
    Blending blending;
    Runnable run;
    String from = "";
}