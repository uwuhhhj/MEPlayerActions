package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class PrivateTextureHeadersTest {
    private static void little(byte[] data,int at,int value,int bytes){for(int i=0;i<bytes;i++)data[at+i]=(byte)(value>>>(8*i));}
    private static void fourcc(byte[] data,int at,String text){for(int i=0;i<4;i++)data[at+i]=(byte)text.charAt(i);}
    private static byte[] bmp(int width,int height){byte[] data=new byte[54];data[0]='B';data[1]='M';little(data,14,40,4);little(data,18,width,4);little(data,22,height,4);return data;}
    private static byte[] webp(int width,int height){byte[] data=new byte[30];fourcc(data,0,"RIFF");little(data,4,22,4);fourcc(data,8,"WEBP");fourcc(data,12,"VP8X");little(data,16,10,4);little(data,24,width-1,3);little(data,27,height-1,3);return data;}
    @Test void nativePngBmpJpegAndWebpDimensionsAreBoundedWithoutImageDecoding() throws IOException {
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        assertEquals(1,PrivateTextureHeaders.pixels("A.PNG",png));assertEquals(128*64,PrivateTextureHeaders.pixels("A.bmp",bmp(128,-64)));
        assertEquals(8192,PrivateTextureHeaders.pixels("A.webp",webp(8192,1)));
        byte[] jpeg={(byte)255,(byte)0xd8,(byte)255,(byte)0xc0,0,8,8,0,64,0,(byte)128,1};assertEquals(128*64,PrivateTextureHeaders.pixels("A.jpg",jpeg));
    }
    @Test void corruptHeadersHugeDimensionsAndPixelBombsAreRejectedBeforeAnyCodecRuns() {
        assertThrows(IOException.class,()->PrivateTextureHeaders.pixels("A.bmp",bmp(8193,1)));
        assertThrows(IOException.class,()->PrivateTextureHeaders.pixels("A.bmp",bmp(1,Integer.MIN_VALUE)));
        assertThrows(IOException.class,()->PrivateTextureHeaders.pixels("A.webp",webp(8192,8192)));
        byte[] malformed=webp(1,1);little(malformed,16,Integer.MAX_VALUE,4);assertThrows(IOException.class,()->PrivateTextureHeaders.pixels("A.webp",malformed));
        assertThrows(IOException.class,()->PrivateTextureHeaders.pixels("A.jpeg",new byte[]{(byte)255,(byte)0xd8,(byte)255,(byte)0xc0,0,40}));
        assertThrows(IOException.class,()->PrivateTextureHeaders.pixels("A.png",new byte[33]));
    }
}
