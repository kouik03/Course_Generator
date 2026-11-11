package course_generator;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

/** Strict desktop-side CGIQ v1 decoder for round-trip verification. */
public final class CourseIqVerifier {
    private CourseIqVerifier() {}

    public static final class Summary {
        public final int vertices, stops, reroutes, eliminations, distance, ascent, descent;
        Summary(int vertices, int stops, int reroutes, int eliminations,
                int distance, int ascent, int descent) {
            this.vertices=vertices;this.stops=stops;this.reroutes=reroutes;
            this.eliminations=eliminations;this.distance=distance;
            this.ascent=ascent;this.descent=descent;
        }
    }

    public static Summary verify(String[] blocks) {
        if (blocks == null || blocks.length != 3) throw new IllegalArgumentException("3 blocs attendus");
        StringBuilder encoded = new StringBuilder();
        for (String part : blocks) {
            if (part == null || part.length() > 2000)
                throw new IllegalArgumentException("Bloc absent ou trop long");
            encoded.append(part);
        }
        byte[] raw;
        try { raw=Base64.getDecoder().decode(encoded.toString()); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException("Base64 invalide",e); }
        if(raw.length<27) throw new IllegalArgumentException("CGIQ trop court");
        CRC32 crc=new CRC32();
        crc.update(raw,0,raw.length-4);
        long expected=((raw[raw.length-4]&255L)<<24) | ((raw[raw.length-3]&255L)<<16)
            | ((raw[raw.length-2]&255L)<<8) | (raw[raw.length-1]&255L);
        if(crc.getValue()!=expected) throw new IllegalArgumentException("CRC32 incorrect");
        try {
            DataInputStream d=new DataInputStream(new ByteArrayInputStream(raw,0,raw.length-4));
            if(d.readByte()!='C'||d.readByte()!='G'||d.readByte()!='I'||d.readByte()!='Q')
                throw new IOException("Signature CGIQ incorrecte");
            if(d.readUnsignedByte()!=1)throw new IOException("Version non prise en charge");
            d.readUnsignedByte(); // flags
            int titleLen=d.readUnsignedByte();
            byte[] title=new byte[titleLen];d.readFully(title);
            new String(title,StandardCharsets.UTF_8);
            int count=d.readUnsignedShort(), stops=d.readUnsignedByte();
            if(count<2||stops<2)throw new IOException("Parcours incomplet");
            int ascent=d.readInt(),descent=d.readInt(),distance=d.readInt();
            if(ascent<0||descent<0||distance<=0)throw new IOException("Totaux incorrects");
            int previousDistance=-1;
            for(int i=0;i<count;i++) {
                d.readInt();d.readInt();int pos=d.readInt();d.readShort();
                d.readInt();d.readInt();d.readInt();
                if(pos<previousDistance)throw new IOException("Sommets désordonnés");
                previousDistance=pos;
            }
            previousDistance=-1;
            int reroutes=0,eliminations=0;
            for(int i=0;i<stops;i++) {
                int pos=d.readInt();d.readInt();int cutoff=d.readInt();
                d.readInt();d.readUnsignedByte();
                int kind=d.readUnsignedByte();
                if(pos<previousDistance||pos>distance)throw new IOException("Points désordonnés");
                if(kind>2 || (kind!=0&&cutoff<=0))throw new IOException("Type de barrière invalide");
                if(kind==2)reroutes++;
                if(kind==1)eliminations++;
                int nameLength=d.readUnsignedByte();
                byte[] name=new byte[nameLength];d.readFully(name);
                previousDistance=pos;
            }
            if(d.available()!=0)throw new IOException("Données supplémentaires");
            return new Summary(count,stops,reroutes,eliminations,distance,ascent,descent);
        } catch(IOException e) { throw new IllegalArgumentException("CGIQ invalide: "+e.getMessage(),e); }
    }
}
