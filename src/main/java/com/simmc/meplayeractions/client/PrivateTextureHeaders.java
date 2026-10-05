package com.simmc.meplayeractions.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/** Bounded image headers only; server never decodes client images through an image codec. */
final class PrivateTextureHeaders {
    private PrivateTextureHeaders() {}
    static long pixels(String path,byte[] data) throws IOException {
        String lower=path.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".png"))return png(data);
        if(lower.endsWith(".bmp"))return bmp(data);
        if(lower.endsWith(".jpg") || lower.endsWith(".jpeg"))return jpeg(data);
        if(lower.endsWith(".webp"))return webp(data);
        throw new IOException("bundle_image_format");
    }
    private static long dimensions(long width,long height) throws IOException {
        if(width<1 || height<1 || width>8192 || height>8192 || width*height>16_777_216)throw new IOException("bundle_texture_size");
        return width*height;
    }
    private static long png(byte[] data) throws IOException {
        byte[] magic={(byte)137,80,78,71,13,10,26,10};
        if(data.length<33 || !Arrays.equals(Arrays.copyOf(data,8),magic) || !fourcc(data,12,"IHDR"))throw new IOException("bundle_png");
        ByteBuffer buffer=ByteBuffer.wrap(data);return dimensions(buffer.getInt(16),buffer.getInt(20));
    }
    private static long bmp(byte[] data) throws IOException {
        if(data.length<26 || data[0]!='B' || data[1]!='M')throw new IOException("bundle_bmp");
        long size=u32(data,14);
        if(size==12)return dimensions(u16(data,18),u16(data,20));
        if(size<40 || size>data.length-14)throw new IOException("bundle_bmp");
        return dimensions((int)u32(data,18),Math.abs((long)(int)u32(data,22)));
    }
    private static long jpeg(byte[] data) throws IOException {
        if(data.length<4 || (data[0]&255)!=255 || (data[1]&255)!=0xd8)throw new IOException("bundle_jpeg");
        int at=2,segments=0;
        while(at<data.length && ++segments<=4096) {
            if((data[at++]&255)!=255)throw new IOException("bundle_jpeg");
            while(at<data.length && (data[at]&255)==255)at++;
            if(at>=data.length)break;int marker=data[at++]&255;
            if(marker==0xd9 || marker==0xda)break;
            if(marker==0 || marker==0xd8)throw new IOException("bundle_jpeg");
            if(marker==1 || marker>=0xd0 && marker<=0xd7)continue;
            if(at+2>data.length)break;int length=((data[at]&255)<<8)|(data[at+1]&255);
            if(length<2 || length>data.length-at)throw new IOException("bundle_jpeg");
            if(marker>=0xc0 && marker<=0xcf && marker!=0xc4 && marker!=0xc8 && marker!=0xcc) {
                if(length<8)throw new IOException("bundle_jpeg");
                int height=((data[at+3]&255)<<8)|(data[at+4]&255),width=((data[at+5]&255)<<8)|(data[at+6]&255);
                return dimensions(width,height);
            }
            at+=length;
        }
        throw new IOException("bundle_jpeg");
    }
    private static long webp(byte[] data) throws IOException {
        if(data.length<20 || !fourcc(data,0,"RIFF") || !fourcc(data,8,"WEBP") || u32(data,4)+8!=data.length)throw new IOException("bundle_webp");
        long pixels=0;int at=12,chunks=0;
        while(at<data.length && ++chunks<=256) {
            if(at+8>data.length)throw new IOException("bundle_webp");
            long length=u32(data,at+4);long next=(long)at+8+length+(length&1);
            if(next>data.length || length>Integer.MAX_VALUE)throw new IOException("bundle_webp");int payload=at+8;
            if(fourcc(data,at,"VP8X")) {
                if(length!=10)throw new IOException("bundle_webp");pixels=Math.max(pixels,dimensions(1+u24(data,payload+4),1+u24(data,payload+7)));
            } else if(fourcc(data,at,"VP8L")) {
                if(length<5 || (data[payload]&255)!=0x2f)throw new IOException("bundle_webp");long bits=u32(data,payload+1);
                if((bits>>>29)!=0)throw new IOException("bundle_webp");pixels=Math.max(pixels,dimensions((bits&0x3fff)+1,((bits>>>14)&0x3fff)+1));
            } else if(fourcc(data,at,"VP8 ")) {
                if(length<10 || (data[payload]&1)!=0 || (data[payload+3]&255)!=0x9d || data[payload+4]!=1 || data[payload+5]!=0x2a)throw new IOException("bundle_webp");
                pixels=Math.max(pixels,dimensions(u16(data,payload+6)&0x3fff,u16(data,payload+8)&0x3fff));
            } else if(fourcc(data,at,"ANMF")) {
                if(length<16)throw new IOException("bundle_webp");dimensions(1+u24(data,payload+6),1+u24(data,payload+9));
            }
            at=(int)next;
        }
        if(at!=data.length || pixels==0)throw new IOException("bundle_webp");return pixels;
    }
    private static boolean fourcc(byte[] data,int at,String value) {
        if(at<0 || at+4>data.length)return false;
        for(int i=0;i<4;i++)if((data[at+i]&255)!=value.charAt(i))return false;return true;
    }
    private static int u16(byte[] data,int at){return(data[at]&255)|((data[at+1]&255)<<8);}
    private static long u32(byte[] data,int at){return Integer.toUnsignedLong((data[at]&255)|((data[at+1]&255)<<8)|((data[at+2]&255)<<16)|((data[at+3]&255)<<24));}
    private static int u24(byte[] data,int at){return(data[at]&255)|((data[at+1]&255)<<8)|((data[at+2]&255)<<16);}
}
