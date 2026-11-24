package com.nura.ld36diagnostic;

public final class RotationGeometrySmokeTest {
    private static void equal(float a,float b) {
        if (Math.abs(a-b)>0.00001f) throw new AssertionError(a+" != "+b);
    }
    private static void verify(int angle) {
        float[] uv=RotationGeometry.uv(angle);
        if (uv.length!=8) throw new AssertionError("UV length");
        for(float x:uv)if(x!=0f&&x!=1f)throw new AssertionError("UV range");
        for(int i=0;i<4;i++)for(int j=i+1;j<4;j++)
            if(uv[i*2]==uv[j*2]&&uv[i*2+1]==uv[j*2+1])
                throw new AssertionError("duplicate corners");
        float[] quad=RotationGeometry.quad(1080,2400,1280,720,angle);
        double visibleWidth=(quad[2]-quad[0])*1280.0;
        double visibleHeight=(quad[5]-quad[1])*720.0;
        double expected=(angle%180==0)?1080.0/2400.0:2400.0/1080.0;
        if(Math.abs(visibleWidth/visibleHeight-expected)>0.0005)
            throw new AssertionError("ratio "+angle);
    }
    public static void main(String[] a) {
        verify(0);verify(90);verify(180);verify(270);
        if(RotationGeometry.normalize(-90)!=270 || RotationGeometry.normalize(450)!=90)
            throw new AssertionError("angle normalize");
        float[] o=RotationGeometry.uv(0);
        equal(o[0],0);equal(o[1],0);equal(o[2],1);equal(o[3],0);
        float[] q=RotationGeometry.uv(90);
        equal(q[0],0);equal(q[1],1);equal(q[2],0);equal(q[3],0);
        boolean rejected=false;
        try { RotationGeometry.uv(10); } catch(IllegalArgumentException ex){rejected=true;}
        if(!rejected)throw new AssertionError("non right angle accepted");
        System.out.println("Rotation UV geometry tests passed");
    }
}
