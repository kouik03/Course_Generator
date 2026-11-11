package course_generator;

import static org.junit.Assert.*;
import java.util.Base64;
import java.util.zip.CRC32;
import org.junit.Test;
import course_generator.utils.CgConst;

/** Regression tests for the three-block CGIQ transport and cutoff semantics. */
public class CourseIqEncoderTest {
    @Test public void rerouteTagIsCumulative() {
        int flags=CgConst.TAG_REROUTE | CgConst.TAG_WATER_PT | CgConst.TAG_EAT_PT;
        assertTrue((flags & CgConst.TAG_REROUTE)!=0);
        assertTrue((flags & CgConst.TAG_WATER_PT)!=0);
        assertTrue((flags & CgConst.TAG_EAT_PT)!=0);
    }

    @Test public void encodeSmallCourseAndVerifyCrc() {
        TrackData track=new TrackData();
        track.data.clear();
        CgData a=new CgData(), b=new CgData(), c=new CgData();
        a.setLatitude(43.0); a.setLongitude(4.0); a.setElevation(100);
        a.setTotal(0); a.setTime(0); a.setName("Départ");
        b.setLatitude(43.01); b.setLongitude(4.01); b.setElevation(170);
        b.setTotal(1500); b.setTime(900); b.setName("Déroutage");
        b.setTag(CgConst.TAG_REROUTE | CgConst.TAG_WATER_PT);
        b.setTimeLimit(1800);
        c.setLatitude(43.02); c.setLongitude(4.02); c.setElevation(110);
        c.setTotal(3000); c.setTime(2000); c.setName("Arrivée");
        track.data.add(a);track.data.add(b);track.data.add(c);
        CourseIqEncoder.Result result=CourseIqEncoder.encode(track);
        assertEquals(3,result.blocks.length);
        for(String block:result.blocks)assertTrue(block.length()<=2000);
        byte[] bytes=Base64.getDecoder().decode(
            result.blocks[0]+result.blocks[1]+result.blocks[2]);
        assertEquals('C',bytes[0]);assertEquals('G',bytes[1]);
        assertEquals('I',bytes[2]);assertEquals('Q',bytes[3]);
        CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-4);
        long expected=((long)(bytes[bytes.length-4]&255)<<24)|
            ((long)(bytes[bytes.length-3]&255)<<16)|
            ((long)(bytes[bytes.length-2]&255)<<8)|(bytes[bytes.length-1]&255);
        assertEquals(expected,crc.getValue());
        assertEquals(3,result.checkpoints);
    }
}
