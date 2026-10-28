package com.easyback.files;

import com.easyback.identity.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileValidationTest {
    private byte[] bytes(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private String design(String name,byte[] bytes) { return FileValidation.type(name,bytes,FileService.Purpose.DESIGN); }
    private static final String TRIANGLE="solid triangle\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid triangle\n";
    @Test void acceptsAsciiAndBinaryStlButRejectsTruncatedAndNonFiniteGeometry() {
        assertEquals("model/stl",design("part.STL",bytes(TRIANGLE)));
        var binary=ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN); binary.putInt(80,1);
        assertEquals("model/stl",design("part.stl",binary.array()));
        binary.putInt(80,2); assertThrows(ApiException.class,()->design("part.stl",binary.array()));
        binary.putInt(80,1); binary.putFloat(84,Float.NaN); assertThrows(ApiException.class,()->design("part.stl",binary.array()));
        assertThrows(ApiException.class,()->design("part.stl",bytes(TRIANGLE.replace("vertex 0 0 0","vertex NaN 0 0"))));
        assertThrows(ApiException.class,()->design("part.stl",bytes(TRIANGLE.replace("endfacet",""))));
        assertThrows(ApiException.class,()->design("part.stl",bytes("MZ this is an executable")));
    }
    private byte[] glb(String json) {
        String padded=json+" ".repeat((4-json.length()%4)%4); byte[] data=bytes(padded);
        return ByteBuffer.allocate(20+data.length).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2)
            .putInt(20+data.length).putInt(data.length).putInt(0x4e4f534a).put(data).array();
    }
    @Test void acceptsSelfContainedGlbAndRejectsInvalidContainersAndRemoteResources() {
        byte[] valid=glb("{\"asset\":{\"version\":\"2.0\"}}");
        assertEquals("model/gltf-binary",design("model.glb",valid));
        ByteBuffer.wrap(valid).order(ByteOrder.LITTLE_ENDIAN).putInt(8,valid.length+4);
        assertThrows(ApiException.class,()->design("model.glb",valid));
        assertThrows(ApiException.class,()->design("model.glb",glb("{\"asset\":{\"version\":\"1.0\"}}")));
        assertThrows(ApiException.class,()->design("model.glb",glb("{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"uri\":\"https://example.com/model.bin\"}]}")));
        for(String extension:java.util.List.of("KHR_draco_mesh_compression","EXT_meshopt_compression","KHR_texture_basisu"))
            assertThrows(ApiException.class,()->design("model.glb",glb("{\"asset\":{\"version\":\"2.0\"},\"extensionsRequired\":[\""+extension+"\"]}")));
        assertThrows(ApiException.class,()->design("model.glb",bytes("MZ renamed executable")));
    }
    @Test void validatesNativeCadSignaturesAndStepEnvelope() {
        var nativeFile=ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        nativeFile.putLong(0,0xe11ab1a1e011cfd0L).putShort(26,(short)3).putShort(28,(short)0xfffe).putShort(30,(short)9).putShort(32,(short)6);
        assertEquals("application/x-solidworks",design("machine.sldasm",nativeFile.array()));
        assertThrows(ApiException.class,()->design("machine.sldprt",bytes("MZ renamed executable")));
        assertEquals("model/step",design("machine.step",bytes("ISO-10303-21;\nHEADER;\nENDSEC;\nDATA;\nENDSEC;\nEND-ISO-10303-21;")));
        assertThrows(ApiException.class,()->design("machine.stp",bytes("ISO-10303-21; HEADER; DATA;")));
        assertThrows(ApiException.class,()->design("machine.exe",nativeFile.array()));
    }
    @Test void previewsAreDecodedImagesAndExistingPdfAttachmentsRemainAccepted() throws Exception {
        var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);
        assertEquals("image/png",FileValidation.type("preview.png",out.toByteArray(),FileService.Purpose.PREVIEW));
        byte[] pdf=bytes("%PDF-1.4\n%%EOF");
        assertEquals("application/pdf",FileValidation.type("drawing.pdf",pdf,FileService.Purpose.ATTACHMENT));
        assertThrows(ApiException.class,()->FileValidation.type("preview.png",pdf,FileService.Purpose.PREVIEW));
        assertThrows(ApiException.class,()->FileValidation.type("preview.jpg",bytes("MZ renamed executable"),FileService.Purpose.PREVIEW));
        assertThrows(ApiException.class,()->FileValidation.type("preview.png",new byte[]{(byte)0x89,'P','N','G'},FileService.Purpose.PREVIEW));
    }
}
