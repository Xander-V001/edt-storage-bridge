package org.xander.edt.storagebridge.ui;

import java.io.IOException;

import org.eclipse.core.resources.IProject;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Per-project settings and persisted revision status.
 * No remote connection or repository mutation is initiated from this dialog.
 */
public final class StorageSettingsDialog extends TitleAreaDialog {

    private final IProject project;
    private Text address;
    private Text username;
    private Text password;
    private StorageProjectSettings.Snapshot saved;

    public StorageSettingsDialog(Shell shell, IProject project) {
        super(shell);
        this.project = project;
        setHelpAvailable(false);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Работа с хранилищем — " + project.getName());
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite root = (Composite) super.createDialogArea(parent);
        setTitle("Работа с хранилищем");
        setMessage("Настройки проекта «" + project.getName() + "». Пароль хранится в Secure Storage EDT.");
        try {
            saved = StorageProjectSettings.load(project);
        } catch (StorageException | RuntimeException ex) {
            setErrorMessage("Не удалось прочитать защищённые настройки. Проверьте Secure Storage EDT.");
            saved = null;
        }

        Composite content = new Composite(root, SWT.NONE);
        content.setLayout(new GridLayout(2, false));
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group connection = new Group(content, SWT.NONE);
        connection.setText("Подключение");
        connection.setLayout(new GridLayout(2, false));
        GridData connectionData = new GridData(SWT.FILL, SWT.TOP, true, false);
        connectionData.horizontalSpan = 2;
        connection.setLayoutData(connectionData);

        address = addText(connection, "Адрес хранилища:", SWT.BORDER,
            saved == null ? "" : saved.address());
        username = addText(connection, "Пользователь:", SWT.BORDER,
            saved == null ? "" : saved.username());
        password = addText(connection, "Пароль:", SWT.BORDER | SWT.PASSWORD,
            saved == null ? "" : saved.password());

        Group revisions = new Group(content, SWT.NONE);
        revisions.setText("Состояние синхронизации");
        revisions.setLayout(new GridLayout(2, false));
        GridData revisionsData = new GridData(SWT.FILL, SWT.TOP, true, false);
        revisionsData.horizontalSpan = 2;
        revisions.setLayoutData(revisionsData);
        addReadOnly(revisions, "Последняя обнаруженная версия хранилища:",
            formatVersion(saved == null ? -1 : saved.observedVersion()));
        addReadOnly(revisions, "Версия, применённая к проекту EDT:",
            formatVersion(saved == null ? -1 : saved.appliedVersion()));

        Label note = new Label(content, SWT.WRAP);
        note.setText("Чтение текущей версии, получение и помещение изменений "
            + "будут доступны после подключения механизма обмена. "
            + "Сейчас окно сохраняет только настройки; "
            + "версии вручную не увеличиваются.");
        GridData noteData = new GridData(SWT.FILL, SWT.TOP, true, false);
        noteData.horizontalSpan = 2;
        noteData.widthHint = 560;
        note.setLayoutData(noteData);
        return root;
    }

    private static Text addText(Composite parent, String caption, int style, String initial) {
        new Label(parent, SWT.NONE).setText(caption);
        Text input = new Text(parent, style);
        input.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        input.setText(initial);
        return input;
    }

    private static void addReadOnly(Composite parent, String caption, String value) {
        new Label(parent, SWT.NONE).setText(caption);
        Label display = new Label(parent, SWT.NONE);
        display.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        display.setText(value);
    }

    private static String formatVersion(long version) {
        return version >= 1 ? Long.toString(version) : "Не определена";
    }

    @Override
    protected void okPressed() {
        if (saved == null) {
            setErrorMessage("Настройки не были загружены. Сохранение запрещено.");
            return;
        }
        try {
            StorageProjectSettings.save(project, address.getText(),
                username.getText(), password.getText());
            super.okPressed();
        } catch (IllegalArgumentException ex) {
            setErrorMessage(ex.getMessage());
        } catch (StorageException | IOException | BackingStoreException | RuntimeException ex) {
            // Do not output exception message: a third-party provider may include secrets.
            setErrorMessage("Не удалось сохранить настройки. Проверьте доступ к "
                + "Secure Storage и настройкам рабочей области.");
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Сохранить", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false);
    }
}
