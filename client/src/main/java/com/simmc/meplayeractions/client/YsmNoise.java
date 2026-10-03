package com.simmc.meplayeractions.client;

/**
 * Pure Java float-compatible implementation of the seeded STB Perlin v0.5 primitive
 * used by OpenYSM 0306e1f. STB tables/algorithm: Sean Barrett, public-domain alternative
 * of https://github.com/nothings/stb/blob/master/stb_perlin.h (Unlicense).
 */
public final class YsmNoise {
    private YsmNoise() { }
    private static final int[] PERM = {
        23,125,161,52,103,117,70,37,247,101,203,169,124,126,44,123,
        152,238,145,45,171,114,253,10,192,136,4,157,249,30,35,72,
        175,63,77,90,181,16,96,111,133,104,75,162,93,56,66,240,
        8,50,84,229,49,210,173,239,141,1,87,18,2,198,143,57,
        225,160,58,217,168,206,245,204,199,6,73,60,20,230,211,233,
        94,200,88,9,74,155,33,15,219,130,226,202,83,236,42,172,
        165,218,55,222,46,107,98,154,109,67,196,178,127,158,13,243,
        65,79,166,248,25,224,115,80,68,51,184,128,232,208,151,122,
        26,212,105,43,179,213,235,148,146,89,14,195,28,78,112,76,
        250,47,24,251,140,108,186,190,228,170,183,139,39,188,244,246,
        132,48,119,144,180,138,134,193,82,182,120,121,86,220,209,3,
        91,241,149,85,205,150,113,216,31,100,41,164,177,214,153,231,
        38,71,185,174,97,201,29,95,7,92,54,254,191,118,34,221,
        131,11,163,99,234,81,227,147,156,176,17,142,69,12,110,62,
        27,255,0,194,59,116,242,252,19,21,187,53,207,129,64,135,
        61,40,167,237,102,223,106,159,197,189,215,137,36,32,22,5
    };
    private static final int[] GRAD = {
        7,9,5,0,11,1,6,9,3,9,11,1,8,10,4,7,
        8,6,1,5,3,10,9,10,0,8,4,1,5,2,7,8,
        7,11,9,10,1,0,4,7,5,0,11,6,1,4,2,8,
        8,10,4,9,9,2,5,7,9,1,7,2,2,6,11,5,
        5,4,6,9,0,1,1,0,7,6,9,8,4,10,3,1,
        2,8,8,9,10,11,5,11,11,2,6,10,3,4,2,4,
        9,10,3,2,6,3,6,10,5,3,4,10,11,2,9,11,
        1,11,10,4,9,4,11,0,4,11,4,0,0,0,7,6,
        10,4,1,3,11,5,3,4,2,9,1,3,0,1,8,0,
        6,7,8,7,0,4,6,10,8,2,3,11,11,8,0,2,
        4,8,3,0,0,10,6,1,2,2,4,5,6,0,1,3,
        11,9,5,5,9,6,9,8,3,8,1,8,9,6,9,11,
        10,7,5,6,5,9,1,3,7,0,2,10,11,2,6,1,
        3,11,7,7,2,1,7,3,0,8,1,1,5,0,6,10,
        11,11,0,2,7,0,10,8,3,5,7,1,11,1,0,7,
        9,0,11,5,10,3,2,3,5,9,7,9,8,4,6,5
    };
    /** OpenYSM coerces coordinates to float and STB uses only the seed's lowest byte. */
    public static float sample(int seed,double xValue,double yValue,double zValue) {
        float x=coordinate(xValue),y=coordinate(yValue),z=coordinate(zValue);
        int px=(int)Math.floor(x),py=(int)Math.floor(y),pz=(int)Math.floor(z);
        int x0=px&255,x1=(px+1)&255,y0=py&255,y1=(py+1)&255,z0=pz&255,z1=(pz+1)&255;
        x-=px;y-=py;z-=pz;
        float u=ease(x),v=ease(y),w=ease(z);
        int r0=perm(x0+(seed&255)),r1=perm(x1+(seed&255));
        int r00=perm(r0+y0),r01=perm(r0+y1),r10=perm(r1+y0),r11=perm(r1+y1);
        float n00=lerp(grad(r00+z0,x,y,z),grad(r00+z1,x,y,z-1),w);
        float n01=lerp(grad(r01+z0,x,y-1,z),grad(r01+z1,x,y-1,z-1),w);
        float n10=lerp(grad(r10+z0,x-1,y,z),grad(r10+z1,x-1,y,z-1),w);
        float n11=lerp(grad(r11+z0,x-1,y-1,z),grad(r11+z1,x-1,y-1,z-1),w);
        return lerp(lerp(n00,n01,v),lerp(n10,n11,v),u);
    }
    private static float coordinate(double value) {
        if(!Double.isFinite(value)||Math.abs(value)>1_000_000_000)
            throw new IllegalArgumentException("Perlin model query coordinate");
        return (float)value;
    }
    private static int perm(int value){return PERM[value&255];}
    private static float ease(float value){return (((value*6-15)*value+10)*value*value*value);}
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
    private static float grad(int index,float x,float y,float z) {
        return switch(GRAD[index&255]) {
            case 0->x+y;case 1->-x+y;case 2->x-y;case 3->-x-y;
            case 4->x+z;case 5->-x+z;case 6->x-z;case 7->-x-z;
            case 8->y+z;case 9->-y+z;case 10->y-z;case 11->-y-z;
            default->throw new AssertionError("STB gradient");
        };
    }
}
