package org.xander.edt.storagebridge.ui;

import java.io.IOException;

import org.eclipse.core.resources.IProject;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.osgi.service.prefs.BackingStoreException;
import org.xander.edt.storagebridge.ui.StorageProjectSettings.InfobaseType;

/**
 * Per-project settings and persisted revision status.
 * No remote connection or repository mutation is initiated from this dialog.
 */
public final class StorageSettingsDialog extends TitleAreaDialog {

    private final IProject project;
    private Text address;
    private Text username;
    private Text password;
    private Combo infobaseType;
    private Text infobaseAddress;
    private Text infobaseUsername;
    private Text infobasePassword;
    private Text platformExecutable;
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

        Group platform = new Group(content, SWT.NONE);
        platform.setText("База 1С, подключённая к хранилищу");
        platform.setLayout(new GridLayout(3, false));
        GridData platformData = new GridData(SWT.FILL, SWT.TOP, true, false);
        platformData.horizontalSpan = 2;
        platform.setLayoutData(platformData);

        new Label(platform, SWT.NONE).setText("Тип информационной базы:");
        infobaseType = new Combo(platform, SWT.DROP_DOWN | SWT.READ_ONLY);
        infobaseType.setItems(new String[] { "Файловая (/F)", "Серверная (/S)" });
        infobaseType.select(saved != null && saved.infobaseType() == InfobaseType.SERVER ? 1 : 0);
        GridData typeData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        typeData.horizontalSpan = 2;
        infobaseType.setLayoutData(typeData);

        new Label(platform, SWT.NONE).setText("Адрес базы-посредника:");
        infobaseAddress = new Text(platform, SWT.BORDER);
        infobaseAddress.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        infobaseAddress.setText(saved == null ? "" : saved.infobaseAddress());
        infobaseUsername = addText(platform, "Пользователь информационной базы (/N):",
            SWT.BORDER, saved == null ? "" : saved.infobaseUsername());
        new Label(platform, SWT.NONE).setText("");
        infobasePassword = addText(platform, "Пароль информационной базы (/P):",
            SWT.BORDER | SWT.PASSWORD,
            saved == null ? "" : saved.infobasePassword());
        new Label(platform, SWT.NONE).setText("");
        Button browseBase = new Button(platform, SWT.PUSH);
        browseBase.setText("Обзор...");
        browseBase.setEnabled(infobaseType.getSelectionIndex() == 0);
        browseBase.addListener(SWT.Selection, event -> {
            DirectoryDialog chooser = new DirectoryDialog(getShell());
            chooser.setText("Выберите каталог файловой информационной базы");
            String selection = chooser.open();
            if (selection != null) {
                infobaseAddress.setText(selection);
            }
        });
        infobaseType.addListener(SWT.Selection, event ->
            browseBase.setEnabled(infobaseType.getSelectionIndex() == 0));

        new Label(platform, SWT.NONE).setText("Платформа 1С (1cv8.exe):");
        platformExecutable = new Text(platform, SWT.BORDER);
        platformExecutable.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        platformExecutable.setText(saved == null ? "" : saved.platformExecutable());
        Button browseExecutable = new Button(platform, SWT.PUSH);
        browseExecutable.setText("Обзор...");
        browseExecutable.addListener(SWT.Selection, event -> {
            FileDialog chooser = new FileDialog(getShell(), SWT.OPEN);
            chooser.setText("Выберите исполняемый файл Конфигуратора 1С");
            chooser.setFilterExtensions(new String[] { "1cv8.exe", "*.exe", "*.*" });
            String selection = chooser.open();
            if (selection != null) {
                platformExecutable.setText(selection);
            }
        });

        Label hint = new Label(platform, SWT.WRAP);
        hint.setText("Учетная запись ИБ не равна пользователю хранилища. "
            + "Поля ИБ оставьте пустыми только если 1С допускает вход без пароля. "
            + "Для файловой базы — каталог с 1Cv8.1CD; для серверной — "
            + "адрес вида server/ibName. Выбирайте ту версию платформы, "
            + "которая уже работает с вашим хранилищем. "
            + "Эта база нужна для работы с хранилищем. "
            + "Другие базы, подключённые к проекту EDT, не используются. "
            + "Сохранение полей не запускает Конфигуратор.");
        GridData hintData = new GridData(SWT.FILL, SWT.TOP, true, false);
        hintData.horizontalSpan = 3;
        hintData.widthHint = 560;
        hint.setLayoutData(hintData);

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
        note.setText("Получение обновит только существующую базу хранилища "
            + "после подтверждения. Затем можно выбрать отдельные объекты "
            + "для сравнения и объединения с EDT. Непринятые объекты "
            + "останутся в журнале. Другие базы EDT не используются.");
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

    private static final int RECEIVE_BUTTON_ID = IDialogConstants.CLIENT_ID + 1;

    @Override
    protected void okPressed() {
        if (saveSettings()) {
            super.okPressed();
        }
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == RECEIVE_BUTTON_ID) {
            if (saveSettings()) {
                try {
                    StorageProjectSettings.Snapshot current =
                        StorageProjectSettings.load(project);
                    // A JFace TitleAreaDialog is modal. Opening EDT's merge
                    // editor while this dialog remains open would block the
                    // user's ability to interact with the merge editor.
                    Composite dialogParent = getShell().getParent();
                    Shell workbenchShell = dialogParent == null
                        ? null : dialogParent.getShell();
                    if (workbenchShell == null || workbenchShell.isDisposed()) {
                        setErrorMessage("Окно EDT недоступно для получения изменений.");
                        return;
                    }
                    close();
                    workbenchShell.getDisplay().asyncExec(() -> {
                        if (!workbenchShell.isDisposed() && project.isAccessible()) {
                            ReceiveChangesCoordinator.receive(
                                workbenchShell, project, current);
                        }
                    });
                } catch (StorageException ex) {
                    setErrorMessage("Не удалось получить настройки Secure Storage.");
                }
            }
            return;
        }
        super.buttonPressed(buttonId);
    }

    private boolean saveSettings() {
        if (saved == null) {
            setErrorMessage("Настройки не были загружены. Сохранение запрещено.");
            return false;
        }
        try {
            StorageProjectSettings.save(project, address.getText(),
                username.getText(), password.getText(),
                infobaseType.getSelectionIndex() == 1
                    ? InfobaseType.SERVER : InfobaseType.FILE,
                infobaseAddress.getText(), platformExecutable.getText(),
                infobaseUsername.getText(), infobasePassword.getText());
            setErrorMessage(null);
            return true;
        } catch (IllegalArgumentException ex) {
            setErrorMessage(ex.getMessage());
        } catch (StorageException | IOException | BackingStoreException | RuntimeException ex) {
            // Avoid leaking text from third-party secrets or providers.
            setErrorMessage("Не удалось сохранить настройки. Проверьте доступ к "
                + "Secure Storage и настройкам рабочей области.");
        }
        return false;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        Button receive = createButton(parent, RECEIVE_BUTTON_ID,
            "Получить изменения", false);
        receive.setEnabled(saved != null);
        receive.setToolTipText("Выборочное получение с подтверждением "
            + "обновления базы, ручным выбором объектов и штатным сравнением EDT.");
        createButton(parent, IDialogConstants.OK_ID, "Сохранить", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false);
    }
}
