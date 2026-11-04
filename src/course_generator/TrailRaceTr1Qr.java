/*
 * Course Generator - local QR codes for TrailRace+ PRO transfer.
 *
 * Every QR contains the exact TR1 part text; no prefix, URL, delimiter or
 * block index is inserted in the encoded payload. The label is only in Swing.
 */
package course_generator;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

public final class TrailRaceTr1Qr {
    // 2000 Base64/ASCII characters fit QR byte mode at M error correction.
    // Large raster and 4-module quiet zone for very dense QR payloads.
    public static final int IMAGE_SIZE = 1800;
    public static final int MAX_BLOCK_LENGTH = 2000;

    private TrailRaceTr1Qr() { }

    public static BufferedImage createAndVerify(String part) throws WriterException, NotFoundException {
        if (part == null || part.isEmpty() || part.length() > MAX_BLOCK_LENGTH)
            throw new IllegalArgumentException("Chaque bloc TR1 doit contenir de 1 à 2000 caractères.");
        // The Base64 format is ASCII. Reject silently normalized/non-ASCII data.
        for (int i = 0; i < part.length(); i++) {
            char value = part.charAt(i);
            if (value > 0x7f)
                throw new IllegalArgumentException("Le code TR1 doit être en ASCII.");
        }

        Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "ISO-8859-1");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 4);
        BitMatrix matrix = new QRCodeWriter().encode(part, BarcodeFormat.QR_CODE,
                IMAGE_SIZE, IMAGE_SIZE, hints);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);

        Map<DecodeHintType, Object> readHints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        readHints.put(DecodeHintType.POSSIBLE_FORMATS,
                java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
        // The generic finder may fail on a dense, perfectly aligned raster.
        // Try normal camera-style detection first; for our generated clean
        // bitmap, PURE_BARCODE decodes the QR modules directly and proves the
        // payload is byte-for-byte correct even if the finder struggles.
        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        readHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        Result result;
        try {
            result = new MultiFormatReader().decode(bitmap, readHints);
        } catch (NotFoundException difficultToLocate) {
            readHints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
            result = new MultiFormatReader().decode(bitmap, readHints);
        }
        if (!part.equals(result.getText()))
            throw new IllegalArgumentException("Le QR code décodé diffère du bloc TR1.");
        return image;
    }

    public static void savePng(BufferedImage image, File file) throws IOException {
        if (image == null || file == null)
            throw new IllegalArgumentException("Aucune image QR à enregistrer.");
        if (!ImageIO.write(image, "PNG", file))
            throw new IOException("Encodeur PNG non disponible.");
    }
}
