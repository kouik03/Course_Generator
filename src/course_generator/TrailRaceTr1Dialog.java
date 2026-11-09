/*
 * Course Generator - TrailRace+ PRO TR1 exporter.
 * Uses the user-supplied RouteCode scripts unchanged. The script is loaded at
 * runtime so a local update never requires a Course Generator recompilation.
 */
package course_generator;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import course_generator.utils.CgConst;

/**
 * Self-contained UI and script bridge for TrailRace+ PRO TR1 codes.
 *
 * Code providers:
 *   0 - adaptive script shipped inside the JAR
 *   1 - user-overridable adaptive script from ~/.course_generator/trailrace
 *   2 - locally saved author/site script from the same directory
 *
 * Downloading an author script is explicit, opt-in and never automatically
 * executes it. JavaScript is evaluated with host/IO access disabled.
 */
public final class TrailRaceTr1Dialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private static final String ORIGINAL_RESOURCE =
            "/course_generator/tr1/routecode-site-reference.js";
    private static final String ADAPTIVE_RESOURCE =
            "/course_generator/tr1/routecode-adaptive.js";
    private static final String SITE_URL =
            "https://petrrezek.net/trailrace-pro-generator.html";
    private static final int MAX_REMOTE_BYTES = 256 * 1024;
    private static final int MAX_CODE_CHARS = 6000;

    private final TrackData track;
    private final JComboBox<String> provider = new JComboBox<String>(new String[] {
            "Adaptatif intégré (version livrée)",
            "Adaptatif local (modifiable sans recompilation)",
            "Auteur du site (version locale)"
    });
    private final JTextArea[] codeFields = new JTextArea[3];
    private final JButton[] qrButtons = new JButton[3];
    private Result latestResult;
    private final JLabel report = new JLabel("Aucun code généré");
    private final JProgressBar progress = new JProgressBar();
    private final JButton generate = new JButton("Générer les 3 blocs");
    private final JButton refreshSite = new JButton("Télécharger le JS du site");
    private final JButton importScript = new JButton("Importer un fichier JS");
    private final JButton openFolder = new JButton("Dossier des scripts");

    public static void showDialog(JFrame owner, TrackData track) {
        TrailRaceTr1Dialog dialog = new TrailRaceTr1Dialog(owner, track);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private TrailRaceTr1Dialog(JFrame owner, TrackData track) {
        super(owner, "Export TrailRace+ PRO (TR1)", false);
        this.track = track;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(920, 680);
        setLayout(new BorderLayout(8, 8));

        JPanel north = new JPanel(new BorderLayout(4, 4));
        north.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Encodeur :"));
        controls.add(provider);
        controls.add(generate);
        controls.add(importScript);
        controls.add(refreshSite);
        controls.add(openFolder);
        north.add(controls, BorderLayout.NORTH);
        north.add(new JLabel("Téléchargement manuel uniquement. Les scripts locaux ne modifient pas le projet CGX."),
                  BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(3, 1, 4, 8));
        center.setBorder(BorderFactory.createEmptyBorder(5, 12, 8, 12));
        for (int i = 0; i < 3; i++) {
            final int block = i;
            JPanel pane = new JPanel(new BorderLayout(4, 4));
            JPanel titleBar = new JPanel(new BorderLayout());
            titleBar.add(new JLabel("Bloc " + (i + 1) + " (2000 caractères maximum)"), BorderLayout.WEST);
            JPanel blockActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            JButton copy = new JButton("Copier ce bloc");
            copy.addActionListener(e -> copyText(codeFields[block].getText()));
            blockActions.add(copy);
            qrButtons[i] = new JButton("Afficher le QR code");
            qrButtons[i].setEnabled(false);
            qrButtons[i].addActionListener(e -> showQrCode(block));
            blockActions.add(qrButtons[i]);
            titleBar.add(blockActions, BorderLayout.EAST);
            pane.add(titleBar, BorderLayout.NORTH);
            codeFields[i] = new JTextArea(4, 80);
            codeFields[i].setEditable(false);
            codeFields[i].setLineWrap(true);
            codeFields[i].setWrapStyleWord(false);
            pane.add(new JScrollPane(codeFields[i]), BorderLayout.CENTER);
            center.add(pane);
        }
        add(center, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        bottom.add(report, BorderLayout.CENTER);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        bottom.add(progress, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        generate.addActionListener(e -> generateAsync());
        importScript.addActionListener(e -> importLocalScript());
        refreshSite.addActionListener(e -> refreshAuthorScript());
        openFolder.addActionListener(e -> {
            try {
                Files.createDirectories(scriptDirectory());
                if (Desktop.isDesktopSupported())
                    Desktop.getDesktop().open(scriptDirectory().toFile());
            } catch (IOException ex) { showError(ex); }
        });
    }

    private void showQrCode(int blockIndex) {
        if (latestResult == null || blockIndex >= latestResult.qrImages.length)
            return;
        TrailRaceTr1QrViewer viewer = new TrailRaceTr1QrViewer(this,
                latestResult.qrImages, latestResult.parts, blockIndex);
        viewer.setVisible(true);
    }

    private void generateAsync() {
        if (track == null || track.data.size() < 2) {
            JOptionPane.showMessageDialog(this, "La trace doit contenir au moins deux positions.");
            return;
        }
        // Snapshot UI data on the Swing EDT. Worker never reads mutable track data.
        final Input input;
        try {
            input = Input.from(track);
        } catch (Exception ex) { showError(ex); return; }
        final int source = provider.getSelectedIndex();
        latestResult = null;
        for (int i = 0; i < 3; i++) {
            qrButtons[i].setEnabled(false);
            codeFields[i].setText("");
        }
        report.setText("Création des blocs TR1 et des QR codes…");
        setBusy(true);
        new SwingWorker<Result, Void>() {
            @Override protected Result doInBackground() throws Exception {
                String script = scriptForSource(source);
                return generateCode(script, source != 2, input);
            }
            @Override protected void done() {
                setBusy(false);
                try {
                    Result r = get();
                    latestResult = r;
                    for (int i = 0; i < 3; i++) {
                        codeFields[i].setText(i < r.parts.length ? r.parts[i] : "");
                        qrButtons[i].setEnabled(i < r.qrImages.length);
                    }
                    String message = String.format(Locale.ROOT,
                            "<html><b>%d caractères</b> · %d blocs · %.3f km · D+ profil %.0f m · %d points du plan · CRC OK</html>",
                            r.code.length(), r.parts.length, r.distance / 1000.0, r.ascent, r.planCount);
                    report.setText(message);
                } catch (Exception ex) {
                    showError(ex);
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        generate.setEnabled(!busy);
        refreshSite.setEnabled(!busy);
        importScript.setEnabled(!busy);
        progress.setVisible(busy);
    }

    private void importLocalScript() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Sélectionner un encodeur RouteCode JavaScript");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            byte[] bytes = Files.readAllBytes(chooser.getSelectedFile().toPath());
            if (bytes.length > MAX_REMOTE_BYTES) throw new IOException("Script trop volumineux.");
            String js = new String(bytes, StandardCharsets.UTF_8);
            validateScript(js);
            Path destination = localScript(provider.getSelectedIndex() == 1 ? 1 : 2);
            if (JOptionPane.showConfirmDialog(this,
                    "Installer ce script dans " + destination + " ?",
                    "Confirmer la mise à jour", JOptionPane.OK_CANCEL_OPTION)
                    != JOptionPane.OK_OPTION) return;
            installWithBackup(destination, js);
            report.setText("Script local installé (SHA-256 : " + sha256(bytes) + ")");
        } catch (Exception ex) { showError(ex); }
    }

    private void refreshAuthorScript() {
        final String address = JOptionPane.showInputDialog(this,
                "URL HTTPS de la page ou du JavaScript officiel :",
                SITE_URL);
        if (address == null || address.trim().isEmpty()) return;
        setBusy(true);
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                String script = fetchAuthorJavaScript(new URL(address.trim()));
                validateScript(script);
                return script;
            }
            @Override protected void done() {
                setBusy(false);
                try {
                    String js = get();
                    byte[] bytes = js.getBytes(StandardCharsets.UTF_8);
                    String hash = sha256(bytes);
                    String prompt = "Script du site récupéré et validé (SHA-256 " + hash
                            + ").\nEnregistrer une copie locale ?\n"
                            + "L'ancienne version sera sauvegardée et ne sera jamais exécutée automatiquement.";
                    if (JOptionPane.showConfirmDialog(TrailRaceTr1Dialog.this, prompt,
                            "Installer la version du site", JOptionPane.YES_NO_OPTION)
                            == JOptionPane.YES_OPTION) {
                        installWithBackup(localScript(2), js);
                        provider.setSelectedIndex(2);
                        report.setText("Script de l'auteur enregistré localement : " + hash);
                    }
                } catch (Exception ex) { showError(ex); }
            }
        }.execute();
    }

    private static Path scriptDirectory() {
        return Paths.get(System.getProperty("user.home"), ".course_generator", "trailrace");
    }

    private static Path localScript(int source) {
        return scriptDirectory().resolve(source == 1 ? "routecode-adaptive-local.js" : "routecode-site-local.js");
    }

    private static String scriptForSource(int source) throws IOException {
        if (source == 0) return readResource(ADAPTIVE_RESOURCE);
        Path script = localScript(source);
        if (!Files.isRegularFile(script)) {
            Files.createDirectories(script.getParent());
            String builtin = readResource(source == 1 ? ADAPTIVE_RESOURCE : ORIGINAL_RESOURCE);
            Files.write(script, builtin.getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = Files.readAllBytes(script);
        if (bytes.length > MAX_REMOTE_BYTES) throw new IOException("JS trop volumineux.");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream input = TrailRaceTr1Dialog.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Ressource JS absente du JAR : " + path);
            return new String(readBounded(input, MAX_REMOTE_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static void installWithBackup(Path destination, String js) throws IOException {
        Files.createDirectories(destination.getParent());
        if (Files.exists(destination)) {
            Path backup = destination.resolveSibling(destination.getFileName()
                    + ".bak-" + System.currentTimeMillis());
            Files.copy(destination, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path temp = Files.createTempFile(destination.getParent(), "routecode-", ".js.tmp");
        try {
            Files.write(temp, js.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String fetchAuthorJavaScript(URL url) throws Exception {
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"petrrezek.net".equalsIgnoreCase(url.getHost()))
            throw new IOException("Seul HTTPS sur petrrezek.net est autorisé.");

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "CourseGenerator-TR1/1");
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("Téléchargement HTTP " + status);
            String content;
            try (InputStream in = conn.getInputStream()) {
                content = new String(readBounded(in, MAX_REMOTE_BYTES), StandardCharsets.UTF_8);
            }
            if (!content.toLowerCase(Locale.ROOT).contains("<html")
                    && !content.toLowerCase(Locale.ROOT).contains("<script"))
                return content;

            Matcher links = Pattern.compile(
                "(?is)<script[^>]+src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>").matcher(content);
            while (links.find()) {
                String candidate = links.group(1);
                if (!candidate.toLowerCase(Locale.ROOT).contains("routecode"))
                    continue;
                URL scriptURL = new URL(url, candidate);
                if (scriptURL.toExternalForm().equals(url.toExternalForm())) continue;
                return fetchAuthorJavaScript(scriptURL);
            }
            Matcher inline = Pattern.compile("(?is)<script\\b[^>]*>(.*?)</script>").matcher(content);
            while (inline.find()) {
                String candidate = inline.group(1);
                if (candidate.contains("RouteCode") && candidate.contains("encode")
                        && candidate.contains("decode"))
                    return candidate;
            }
            throw new IOException("Le site ne publie pas de script RouteCode identifiable. "
                    + "Saisir son URL JS exacte ou importer le fichier manuellement.");
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
            if (out.size() > limit) throw new IOException("Le script dépasse " + limit + " octets.");
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder b = new StringBuilder();
        for (byte v : hash) b.append(String.format(Locale.ROOT, "%02x", v & 255));
        return b.toString();
    }

    private static Context newSandbox() {
        Context context = Context.newBuilder("js")
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(s -> false)
                .allowIO(false)
                .allowCreateThread(false)
                .build();
        try {
            context.eval("js", readResource("/course_generator/tr1/base64-shim.js"));
            return context;
        } catch (IOException ex) {
            context.close();
            throw new IllegalStateException("Cannot load browser-compatible Base64 functions", ex);
        }
    }

    private static Value getRouteCode(Context context, String script) {
        context.eval("js", script);
        Value global = context.getBindings("js").getMember("RouteCode");
        if (global == null || global.isNull()) {
            global = context.eval("js", "typeof RouteCode !== 'undefined' ? RouteCode : null");
        }
        if (global == null || global.isNull()
                || !global.hasMember("encode") || !global.getMember("encode").canExecute()
                || !global.hasMember("decode") || !global.getMember("decode").canExecute())
            throw new IllegalArgumentException("Ce script n'expose pas RouteCode.encode et decode.");
        return global;
    }

    private static void validateScript(String script) {
        try (Context context = newSandbox()) {
            getRouteCode(context, script);
        }
    }

    private static Result generateCode(String script, boolean adaptive, Input input) throws Exception {
        try (Context context = newSandbox()) {
            Value route = getRouteCode(context, script);
            String method = adaptive && route.hasMember("encodeAdaptive")
                    && route.getMember("encodeAdaptive").canExecute() ? "encodeAdaptive" : "encode";
            context.getBindings("js").putMember("__cg_track_json", input.trackJson);
            context.getBindings("js").putMember("__cg_plan_json", input.planJson);
            Value jsTrack = context.eval("js", "JSON.parse(__cg_track_json)");
            Value jsPlan = context.eval("js", "JSON.parse(__cg_plan_json)");
            Value output = route.getMember(method).execute(jsTrack, input.name, jsPlan);
            String code = output.getMember("code").asString();
            if (code.length() > MAX_CODE_CHARS || !code.startsWith("TR1"))
                throw new IllegalArgumentException("Le code dépasse 6000 caractères ou n'est pas TR1.");
            Value decoded = route.getMember("decode").execute(code);
            if (decoded == null || decoded.isNull())
                throw new IllegalArgumentException("Décodage/CRC du TR1 invalide.");
            Value decodedPlan = decoded.getMember("plan");
            if (!decodedPlan.hasArrayElements() || decodedPlan.getArraySize() != input.planCount)
                throw new IllegalArgumentException("Les points du plan ont changé au décodage.");
            Result result = new Result();
            result.code = code;
            result.parts = new String[(code.length() + 1999) / 2000];
            if (result.parts.length == 0 || result.parts.length > 3)
                throw new IllegalArgumentException("Code TR1 non compatible avec trois blocs.");
            for (int i = 0; i < result.parts.length; i++)
                result.parts[i] = code.substring(i * 2000, Math.min(code.length(), (i + 1) * 2000));
            // Generate and immediately decode every QR to verify byte-for-byte
            // identity with the text blocks before offering them for scanning.
            result.qrImages = new BufferedImage[result.parts.length];
            for (int i = 0; i < result.parts.length; i++)
                result.qrImages[i] = TrailRaceTr1Qr.createAndVerify(result.parts[i]);
            result.distance = number(output, "lengthM");
            result.ascent = number(output, "climbM");
            result.planCount = input.planCount;
            return result;
        }
    }

    private static double number(Value v, String key) {
        return v.hasMember(key) && v.getMember(key).isNumber()
                ? v.getMember(key).asDouble() : Double.NaN;
    }

    private static void copyText(String text) {
        if (text == null || text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(text), null);
    }

    private void showError(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        JOptionPane.showMessageDialog(this, String.valueOf(cause.getMessage()),
                "Export TrailRace+ PRO", JOptionPane.ERROR_MESSAGE);
    }

    private static final class Result {
        String code;
        String[] parts;
        BufferedImage[] qrImages;
        double distance;
        double ascent;
        int planCount;
    }

    private static final class Input {
        String trackJson;
        String planJson;
        String name;
        int planCount;

        static Input from(TrackData track) {
            Input input = new Input();
            input.name = track.CourseName != null && !track.CourseName.trim().isEmpty()
                    ? track.CourseName : (track.Name == null ? "Course Generator" : track.Name);

            StringBuilder xy = new StringBuilder("[");
            StringBuilder stops = new StringBuilder("[");
            double lastDist = 0, rawDplus = 0;
            double[] cumulative = new double[track.data.size()];
            for (int i = 0; i < track.data.size(); i++) {
                CgData pt = track.data.get(i);
                if (i > 0) {
                    CgData prev = track.data.get(i - 1);
                    rawDplus += Math.max(0, pt.getElevation(CgConst.UNIT_METER)
                            - prev.getElevation(CgConst.UNIT_METER));
                }
                cumulative[i] = rawDplus;
                if (i > 0) xy.append(',');
                xy.append('[').append(pt.getLatitude()).append(',')
                  .append(pt.getLongitude()).append(',')
                  .append(pt.getElevation(CgConst.UNIT_METER)).append(']');
                lastDist = pt.getTotal(CgConst.UNIT_METER);
            }
            xy.append(']');

            double scale = rawDplus > 0 ? track.getClimbP(CgConst.UNIT_METER) / rawDplus : 1.0;
            int count = 0;
            // TR1 "dp" is the ascent of the leg ending at this checkpoint,
            // not cumulative ascent from the start. Every included point is
            // a leg boundary, including pass-through points and the finish.
            double previousCheckpointDplus = 0.0;
            int lastIndex = track.data.size() - 1;
            for (int i = 0; i < track.data.size(); i++) {
                CgData pt = track.data.get(i);
                int tag = pt.getTag();
                boolean finish = i == lastIndex;
                boolean included = finish
                        || (tag & (CgConst.TAG_ROADBOOK | CgConst.TAG_MARK)) != 0
                        || pt.getStation() > 0 || pt.getTimeLimit() > 0;
                if (!included) continue;
                if (count > 0) stops.append(',');
                String name = pt.getName();
                if (finish && (name == null || name.trim().isEmpty())) name = "Finish";
                if (name == null || name.trim().isEmpty())
                    name = String.format(Locale.ROOT, "Point %.2f km",
                            pt.getTotal(CgConst.UNIT_METER) / 1000.0);
                String service = "";
                if ((tag & CgConst.TAG_WATER_PT) != 0) service += "W";
                if ((tag & CgConst.TAG_EAT_PT) != 0) {
                    if (!service.contains("W")) service += "W"; // food implies water
                    service += "F";
                }
                if ((tag & CgConst.TAG_CREW) != 0) service += "C";
                if ((tag & CgConst.TAG_DROPBAG) != 0) service += "D";
                stops.append("{\"km\":")
                     .append(pt.getTotal(CgConst.UNIT_METER) / 1000.0)
                     .append(",\"name\":").append(jsonString(name))
                     .append(",\"cut\":-1,\"rel\":")
                     .append(pt.getTimeLimit() > 0 ? Math.round(pt.getTimeLimit() / 60.0) : -1)
                     .append(",\"srv\":").append(jsonString(service))
                     .append(",\"dp\":")
                     .append(Math.max(0, Math.round((cumulative[i] - previousCheckpointDplus) * scale)))
                     .append('}');
                previousCheckpointDplus = cumulative[i];
                count++;
            }
            stops.append(']');
            input.trackJson = xy.toString();
            input.planJson = stops.toString();
            input.planCount = count;
            if (lastDist <= 0) throw new IllegalArgumentException("Distance du parcours nulle.");
            if (count > 255) throw new IllegalArgumentException("TR1 accepte au maximum 255 points.");
            return input;
        }

        private static String jsonString(String s) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\"' || ch == '\\') out.append('\\');
                if (ch == '\n') out.append("\\n");
                else if (ch == '\r') out.append("\\r");
                else if (ch == '\t') out.append("\\t");
                else if (ch < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                else out.append(ch);
            }
            return out.append('"').toString();
        }
    }
}
