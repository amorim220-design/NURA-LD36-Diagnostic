package com.nura.ld36diagnostic;

/** Position vertices for aspect-fit (no image crop and no stretching) in a fixed TV frame. */
public final class AspectFit {
    private AspectFit() { }
    /** GLfloat quad vertices in CCW triangle strip order, centered inside [-1,1]. */
    public static float[] vertices(int sw,int sh,int dw,int dh) {
        if (sw<=0||sh<=0||dw<=0||dh<=0) throw new IllegalArgumentException("Invalid dimensions");
        double input=(double)sw/sh, output=(double)dw/dh;
        float x=1f, y=1f;
        if (input>output) y=(float)(output/input);
        else x=(float)(input/output);
        return new float[]{-x,-y,x,-y,-x,y,x,y};
    }
}
