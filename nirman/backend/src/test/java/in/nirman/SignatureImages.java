package in.nirman;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * A signature the tests can upload, and a way to tell whether a page drew one.
 *
 * <p>A real PNG rather than four arbitrary bytes, because the renderer decodes it: the
 * offer letter and the daily report draw the picture into the PDF, and a file the decoder
 * rejects would fail the render rather than test it.</p>
 */
public final class SignatureImages {

    private SignatureImages() {
    }

    /** A 90 by 30 PNG, white with a dark stroke across it: the shape the screen uploads. */
    public static byte[] png() {
        BufferedImage image = new BufferedImage(90, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 90, 30);
        g.setColor(Color.DARK_GRAY);
        g.drawLine(5, 25, 85, 5);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not write a test PNG", e);
        }
    }

    /** How many image objects the page's resources carry. */
    public static int countImages(PDPage page) throws IOException {
        PDResources resources = page.getResources();
        if (resources == null) {
            return 0;
        }
        int count = 0;
        for (COSName name : resources.getXObjectNames()) {
            if (resources.isImageXObject(name)) {
                count++;
            }
        }
        return count;
    }
}
