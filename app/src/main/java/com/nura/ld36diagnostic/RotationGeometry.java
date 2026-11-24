package com.nura.ld36diagnostic;

/**
 * Geometry of the user-selected clockwise rotation of a captured frame.
 * Texture coordinates are passed through SurfaceTexture.getTransformMatrix()
 * by the shader; do not discard Android's transform.
 */
public final class RotationGeometry {
    private RotationGeometry() { }
    public static int normalize(int angle) {
        if (angle % 90 != 0) throw new IllegalArgumentException("Only quarter turns are supported");
        int result = angle % 360;
        return result < 0 ? result + 360 : result;
    }

    public static float[] quad(int width,int height,int outputWidth,int outputHeight,int angle) {
        int rotation=normalize(angle);
        int orientedW = (rotation % 180 == 0) ? width : height;
        int orientedH = (rotation % 180 == 0) ? height : width;
        return AspectFit.vertices(orientedW,orientedH,outputWidth,outputHeight);
    }

    /** Bottom-left, bottom-right, top-left, top-right UV coordinates. */
    public static float[] uv(int degrees) {
        switch(normalize(degrees)) {
            case 0:   return new float[]{0,0, 1,0, 0,1, 1,1};
            case 90:  return new float[]{0,1, 0,0, 1,1, 1,0};
            case 180: return new float[]{1,1, 0,1, 1,0, 0,0};
            case 270: return new float[]{1,0, 1,1, 0,0, 0,1};
            default: throw new AssertionError("unreachable");
        }
    }
}
