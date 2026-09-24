package com.argus.ui;

import com.argus.core.OperatorId;
import com.argus.core.Vault;
import com.argus.core.VaultException;
import com.argus.core.VaultFormatException;
import com.argus.core.VaultNotFoundException;
import com.argus.core.VaultStore;
import com.argus.core.WrongMasterPasswordException;
import java.util.Arrays;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * Login screen. Field wiring, validation dispatch and message display -- no crypto, no file
 * paths, no policy (plan §7.14). FXML requires a no-arg constructor, so dependencies arrive by
 * setter.
 */
public final class LoginController {

    private static final Duration STAGGER_STEP = Duration.millis(40);

    /** "Fade in logo, then login panel" (login screen polish batch): the panel's own stagger
     *  starts after the logo's own fade-in is well under way, so the two read as two beats
     *  instead of one flat stagger, while the whole sequence still lands inside 500-800&nbsp;ms. */
    private static final Duration PANEL_START_DELAY = Duration.millis(200);

    /** Ambient background breathe: slow and shallow, never the fast "live scan" blink
     *  {@link AnimationUtils#pulseDot(javafx.scene.Node)} uses elsewhere. */
    private static final Duration AMBIENT_BREATH_PERIOD = Duration.millis(5000);
    private static final double AMBIENT_MIN_OPACITY = 0.55;

    /** The status dots' own subtle pulse -- distinct from both the ambient breathe and the
     *  logo's glow so the three don't all read as one mechanically synchronized animation. */
    private static final Duration STATUS_PULSE_PERIOD = Duration.millis(2200);
    private static final double STATUS_MIN_OPACITY = 0.4;

    private static final double HERO_ICON_SIZE = 84;

    @FXML
    private Circle ambientGlow;
    @FXML
    private Circle topbarStatusDot;
    @FXML
    private VBox card;
    @FXML
    private StackPane brandIcon;
    @FXML
    private TextField operatorIdField;
    @FXML
    private PasswordField masterPasswordField;
    @FXML
    private TextField masterPasswordVisibleField;
    @FXML
    private Button passwordVisibilityToggle;
    @FXML
    private Button unlockButton;
    @FXML
    private Label messageLabel;

    private VaultStore store;
    private Consumer<Vault> onUnlocked;
    private boolean pendingCreate;
    private boolean passwordMasked = true;

    /** Injected by App before the scene is shown. */
    public void setVaultStore(VaultStore store) {
        this.store = store;
    }

    /** Called on the FX thread with the unlocked vault. App swaps the scene root. */
    public void setOnUnlocked(Consumer<Vault> callback) {
        this.onUnlocked = callback;
    }

    @FXML
    private void initialize() {
        brandIcon.getChildren().add(buildHeroIcon());
        AnimationUtils.glowPulse(brandIcon);
        AnimationUtils.pulseDot(ambientGlow, AMBIENT_BREATH_PERIOD, AMBIENT_MIN_OPACITY);
        AnimationUtils.pulseDot(topbarStatusDot, STATUS_PULSE_PERIOD, STATUS_MIN_OPACITY);

        masterPasswordVisibleField.textProperty()
                .bindBidirectional(masterPasswordField.textProperty());
        applyPasswordFieldVisibility();
        AnimationUtils.bindFocusGlow(operatorIdField);
        AnimationUtils.bindFocusGlow(masterPasswordField);
        AnimationUtils.bindFocusGlow(masterPasswordVisibleField);

        animateEntrance();
        operatorIdField.textProperty().addListener((obs, oldValue, newValue) -> pendingCreate = false);
    }

    /** The official ARGUS application icon (structural redesign batch), loaded once and sized
     *  for the hero position above "SECURE ACCESS" -- the same node {@link #initialize()} hands
     *  to {@link AnimationUtils#glowPulse(javafx.scene.Node)}, so the existing logo glow
     *  animation now plays around this icon instead of the old procedural eye mark. */
    private ImageView buildHeroIcon() {
        ImageView icon = new ImageView(new Image(getClass().getResourceAsStream("argus-icon.png")));
        icon.setFitWidth(HERO_ICON_SIZE);
        icon.setFitHeight(HERO_ICON_SIZE);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        return icon;
    }

    /** Logo first, then the rest of the panel staggered behind it (plan: "fade in logo, then
     *  login panel, about 500-800ms"). */
    private void animateEntrance() {
        AnimationUtils.fadeInUp(brandIcon, Duration.ZERO);
        int index = 0;
        for (var child : card.getChildren()) {
            if (child == brandIcon) {
                continue;
            }
            AnimationUtils.fadeInUp(child, PANEL_START_DELAY.add(STAGGER_STEP.multiply(index)));
            index++;
        }
    }

    @FXML
    private void onTogglePasswordVisibility() {
        passwordMasked = !passwordMasked;
        applyPasswordFieldVisibility();
        var shownField = passwordMasked ? masterPasswordField : masterPasswordVisibleField;
        AnimationUtils.fadeInUp(shownField, Duration.ZERO);
    }

    /** Swaps which of the two bound fields is shown -- {@code masterPasswordField} (masked) or
     *  {@code masterPasswordVisibleField} (plain text, kept in sync via the bidirectional
     *  binding in {@link #initialize()}) -- and updates the toggle glyph to match. Both fields
     *  read the same text either way, so {@link #onUnlock()} always reads
     *  {@code masterPasswordField.getText()} unchanged regardless of which one is on screen. No
     *  animation here -- {@link #initialize()} also calls this once, before the entrance
     *  animation has run, when nothing should be fading in yet. */
    private void applyPasswordFieldVisibility() {
        masterPasswordField.setVisible(passwordMasked);
        masterPasswordField.setManaged(passwordMasked);
        masterPasswordVisibleField.setVisible(!passwordMasked);
        masterPasswordVisibleField.setManaged(!passwordMasked);
        passwordVisibilityToggle.setGraphic(Icons.eyeToggle(passwordMasked));
    }

    @FXML
    private void onUnlock() {
        String operatorIdText = operatorIdField.getText();
        String passwordText = masterPasswordField.getText();
        int passwordLength = passwordText == null ? 0 : passwordText.length();

        LoginValidation.Result result =
                LoginValidation.check(operatorIdText, passwordLength, pendingCreate);
        if (!result.valid()) {
            showMessage(result.message(), true);
            AnimationUtils.shake(card);
            return;
        }

        OperatorId operator = result.operatorId();
        boolean creating = pendingCreate;
        char[] password = passwordText.toCharArray();

        unlockButton.setDisable(true);
        clearMessage();

        Task<Vault> task = new Task<>() {
            @Override
            protected Vault call() throws VaultException {
                try {
                    return creating ? store.create(operator, password) : store.open(operator, password);
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        };

        task.setOnSucceeded(event -> {
            unlockButton.setDisable(false);
            masterPasswordField.clear();
            onUnlocked.accept(task.getValue());
        });

        task.setOnFailed(event -> {
            unlockButton.setDisable(false);
            handleFailure(operator, task.getException());
        });

        Thread thread = new Thread(task, "argus-vault-unlock");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleFailure(OperatorId operator, Throwable exception) {
        if (exception instanceof VaultNotFoundException) {
            pendingCreate = true;
            showMessage("no vault for operator '" + operator
                    + "' - press unlock again to create one", false);
        } else if (exception instanceof WrongMasterPasswordException) {
            showMessage(
                    "operator id or master password is wrong, or the vault file was modified",
                    true);
            AnimationUtils.shake(card);
            masterPasswordField.clear();
        } else if (exception instanceof VaultFormatException) {
            showMessage("the vault file for '" + operator + "' is unreadable", true);
            AnimationUtils.shake(card);
        } else if (exception instanceof VaultException || exception instanceof IllegalArgumentException) {
            showMessage(exception.getMessage(), true);
            AnimationUtils.shake(card);
        } else {
            showMessage("could not open the vault", true);
            AnimationUtils.shake(card);
        }
    }

    private void showMessage(String text, boolean isError) {
        messageLabel.setText(text);
        messageLabel.getStyleClass().removeAll("form-error", "form-hint");
        messageLabel.getStyleClass().add(isError ? "form-error" : "form-hint");
        messageLabel.setVisible(true);
        messageLabel.setManaged(true);
    }

    private void clearMessage() {
        messageLabel.setText(null);
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);
    }
}
