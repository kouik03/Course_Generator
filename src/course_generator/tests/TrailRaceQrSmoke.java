package course_generator.tests;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import course_generator.TrailRaceTr1Qr;

/** QR payload byte-for-byte, including maximum 2000-character TR1 fields. */
public final class TrailRaceQrSmoke {
    public static void main(String[] args) throws Exception {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int length : new int[] {1, 50, 1955, 2000}) {
            StringBuilder payload = new StringBuilder(length);
            for (int i = 0; i < length; i++)
                payload.append(alphabet.charAt((i * 31 + i / 7) % alphabet.length()));
            if (length >= 3) payload.replace(0, 3, "TR1");

            BufferedImage image = TrailRaceTr1Qr.createAndVerify(payload.toString());
            if (image.getWidth() != TrailRaceTr1Qr.IMAGE_SIZE
                    || image.getHeight() != TrailRaceTr1Qr.IMAGE_SIZE)
                throw new AssertionError("Unexpected QR size");

            Path png = Files.createTempFile("trailrace-qr-", ".png");
            try {
                TrailRaceTr1Qr.savePng(image, png.toFile());
                BufferedImage reloaded = ImageIO.read(png.toFile());
                if (reloaded == null || reloaded.getWidth() != image.getWidth())
                    throw new AssertionError("PNG did not re-open");
            } finally {
                Files.deleteIfExists(png);
            }
            System.out.println("TR1 QR " + length + " chars: encode, exact decode, PNG OK");
        }

        boolean tooLongRejected = false;
        try {
            StringBuilder oversize = new StringBuilder();
            while (oversize.length() <= 2000) oversize.append('A');
            TrailRaceTr1Qr.createAndVerify(oversize.toString());
        } catch (IllegalArgumentException expected) {
            tooLongRejected = true;
        }
        if (!tooLongRejected)
            throw new AssertionError("A QR block longer than 2000 chars was accepted.");
    }
}
