package com.easyback.files;

import com.easyback.identity.ApiException;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import tools.jackson.databind.json.JsonMapper;

/** Identify accepted content from bytes; browser MIME types are untrusted. */
final class FileValidation {
    private FileValidation() {}
    private static ApiException invalid() { return new ApiException(400,"FILE_TYPE","Upload a valid PNG/JPEG preview, PDF attachment, or supported CAD design (SolidWorks, STEP, STL, GLB)."); }
    static String type(String filename,byte[] bytes,FileService.Purpose purpose) {
        if(purpose==FileService.Purpose.DESIGN) return design(filename,bytes);
        if(purpose==FileService.Purpose.ATTACHMENT && bytes.length>8 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")
            && new String(bytes,Math.max(0,bytes.length-1024),Math.min(1024,bytes.length),StandardCharsets.ISO_8859_1).contains("%%EOF")) return "application/pdf";
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input); if(!readers.hasNext()) throw new IOException(); var reader=readers.next();
            try {
                reader.setInput(input); String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","jpg").contains(format) || (long)reader.getWidth(0)*reader.getHeight(0)>10000000) throw new IOException();
                reader.read(0); return format.equals("png")?"image/png":"image/jpeg";
            } finally { reader.dispose(); }
        } catch(Exception e) { throw invalid(); }
    }
    private static String ascii(byte[] bytes) { return new String(bytes,StandardCharsets.US_ASCII); }
    private static String design(String filename,byte[] bytes) {
        String ext=filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        try {
            return switch(ext) {
                case "stl" -> { if(!binaryStl(bytes) && !asciiStl(bytes)) throw invalid(); yield "model/stl"; }
                case "glb" -> { glb(bytes); yield "model/gltf-binary"; }
                case "step","stp" -> {
                    String text=ascii(bytes).strip();
                    if(!text.startsWith("ISO-10303-21;") || !text.endsWith("END-ISO-10303-21;")
                        || !text.contains("HEADER;") || !text.contains("DATA;") || !text.contains("ENDSEC;")) throw invalid();
                    yield "model/step";
                }
                case "sldprt","sldasm","slddrw" -> {
                    var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    if(bytes.length<512 || b.getLong(0)!=0xe11ab1a1e011cfd0L || b.getShort(28)!=(short)0xfffe
                        || (b.getShort(26)!=3 && b.getShort(26)!=4) || (b.getShort(30)!=9 && b.getShort(30)!=12) || b.getShort(32)!=6) throw invalid();
                    yield "application/x-solidworks";
                }
                default -> throw invalid();
            };
        } catch(ApiException e) { throw e; } catch(Exception e) { throw invalid(); }
    }
    private static boolean binaryStl(byte[] bytes) {
        if(bytes.length<134) return false;
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long triangles=Integer.toUnsignedLong(b.getInt(80));
        if(triangles==0 || 84+50*triangles!=bytes.length) return false;
        for(int offset=84;offset<bytes.length;offset+=50)
            for(int value=0;value<12;value++) if(!Float.isFinite(b.getFloat(offset+4*value))) return false;
        return true;
    }
    private static boolean asciiStl(byte[] bytes) {
        String text=ascii(bytes).strip();
        if(!text.startsWith("solid") || text.indexOf('\0')>=0) return false;
        int first=text.indexOf('\n'),last=text.lastIndexOf("endsolid");
        if(first<5 || last<=first || !text.substring(last+8).chars().allMatch(c->c>=32 || Character.isWhitespace(c))) return false;
        try {
            var words=new StringTokenizer(text.substring(first+1,last)); int triangles=0;
            while(words.hasMoreTokens()) {
                expect(words,"facet"); expect(words,"normal"); numbers(words,3); expect(words,"outer"); expect(words,"loop");
                for(int i=0;i<3;i++) { expect(words,"vertex"); numbers(words,3); }
                expect(words,"endloop"); expect(words,"endfacet"); triangles++;
            }
            return triangles>0;
        } catch(RuntimeException e) { return false; }
    }
    private static void expect(StringTokenizer words,String value) { if(!words.nextToken().equals(value)) throw invalid(); }
    private static void numbers(StringTokenizer words,int count) {
        for(int i=0;i<count;i++) if(!Double.isFinite(Double.parseDouble(words.nextToken()))) throw invalid();
    }
    private static void glb(byte[] bytes) {
        if(bytes.length<20) throw invalid();
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(b.getInt()!=0x46546c67 || b.getInt()!=2 || Integer.toUnsignedLong(b.getInt())!=bytes.length) throw invalid();
        boolean json=false; int chunks=0;
        while(b.remaining()>0) {
            if(b.remaining()<8) throw invalid(); int length=b.getInt(),type=b.getInt();
            if(length<=0 || length%4!=0 || length>b.remaining()) throw invalid();
            if(chunks++==0) {
                if(type!=0x4e4f534a) throw invalid();
                byte[] data=new byte[length]; b.get(data);
                var document=JsonMapper.builder().build().readTree(data);
                if(!document.path("asset").path("version").asText().equals("2.0")) throw invalid();
                for(var extension:document.path("extensionsRequired"))
                    if(Set.of("KHR_draco_mesh_compression","EXT_meshopt_compression","KHR_texture_basisu").contains(extension.asText())) throw invalid();
                // Uploaded GLB must be self contained. Remote buffers and textures
                // cannot be resolved from the authenticated blob URL in the viewer.
                for(String field:List.of("buffers","images")) for(var item:document.path(field))
                    if(item.has("uri") && !item.path("uri").asText().startsWith("data:")) throw invalid();
                json=true;
            } else {
                if(chunks>2 || type!=0x004e4942) throw invalid(); b.position(b.position()+length);
            }
        }
        if(!json) throw invalid();
    }
}
