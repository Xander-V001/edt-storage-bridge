package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import org.eclipse.swt.widgets.Display;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.osgi.service.prefs.BackingStoreException;

/** Separate MCP tab inside the existing project settings dialog. */
final class BridgeMcpPanel extends Composite {
    private static final int DESCRIPTION_WIDTH = 680;

    private Text portText;
    private Text tokenText;
    private Label state;
    private Label endpoint;
    private Button start;
    private Button stop;
    private Button test;
    private Button autoStart;
    private boolean canSave = true;

    BridgeMcpPanel(Composite parent) {
        super(parent, SWT.NONE);
        setLayout(new GridLayout(1, false));
        setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        createControls();
        updateStatus();
    }

    private void createControls() {
        Group settings = new Group(this, SWT.NONE);
        settings.setText("MCP-сервер EDT Storage Bridge");
        settings.setLayout(new GridLayout(4, false));
        settings.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(settings, SWT.NONE).setText("Адрес:");
        endpoint = new Label(settings, SWT.NONE);
        endpoint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        new Label(settings, SWT.NONE).setText("Порт:");
        portText = new Text(settings, SWT.BORDER);
        GridData portData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        portData.widthHint = 95;
        portText.setLayoutData(portData);
        new Label(settings, SWT.NONE).setText("Только 127.0.0.1");
        new Label(settings, SWT.NONE);
        portText.addModifyListener(event -> updateEndpoint());

        new Label(settings, SWT.NONE).setText("Токен (необязательно):");
        tokenText = new Text(settings, SWT.BORDER | SWT.PASSWORD);
        GridData tokenData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        tokenData.widthHint = 325;
        tokenText.setLayoutData(tokenData);
        Button toggle = new Button(settings, SWT.PUSH);
        toggle.setText("Показать");
        toggle.addListener(SWT.Selection, event -> {
            if (tokenText.getEchoChar() != '\0') {
                tokenText.setEchoChar('\0');
                toggle.setText("Скрыть");
            } else {
                tokenText.setEchoChar('\u2022');
                toggle.setText("Показать");
            }
        });

        Button regenerate = new Button(settings, SWT.PUSH);
        regenerate.setText("Создать токен");
        regenerate.setToolTipText("Создать новый токен доступа для защиты MCP-сервера");
        regenerate.addListener(SWT.Selection, event -> {
            if (BridgeMcpServer.INSTANCE.isRunning()
                    && !MessageDialog.openConfirm(getShell(), "Новый токен MCP",
                        "После смены токена нужно остановить и запустить "
                        + "MCP-сервер, а также обновить настройки MCP-клиентов. "
                        + "Продолжить?")) return;
            tokenText.setText(BridgeMcpSettings.newToken());
        });
        Label scope = new Label(settings, SWT.WRAP);
        scope.setText("Доступ только с этого компьютера (127.0.0.1). "
            + "Без токена локальные программы могут вызывать MCP-инструменты, "
            + "включая изменение проекта и хранилища. "
            + "При необходимости задайте токен.");
        GridData scopeData = new GridData(SWT.FILL, SWT.TOP, true, false, 4, 1);
        scopeData.widthHint = DESCRIPTION_WIDTH;
        scope.setLayoutData(scopeData);

        autoStart = new Button(settings, SWT.CHECK);
        autoStart.setText("Автоматически запускать MCP-сервер при старте EDT");
        autoStart.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 4, 1));
        autoStart.setSelection(true);
        autoStart.addListener(SWT.Selection, event -> updateStatus());

        Group controls = new Group(this, SWT.NONE);
        controls.setText("Управление");
        controls.setLayout(new GridLayout(4, false));
        controls.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        start = new Button(controls, SWT.PUSH);
        start.setText("Запустить");
        stop = new Button(controls, SWT.PUSH);
        stop.setText("Остановить");
        test = new Button(controls, SWT.PUSH);
        test.setText("Проверить");
        Button copy = new Button(controls, SWT.PUSH);
        copy.setText("Копировать MCP-конфигурацию");

        start.addListener(SWT.Selection, event -> startServer());
        stop.addListener(SWT.Selection, event -> {
            BridgeMcpServer.INSTANCE.close();
            updateStatus();
        });
        test.addListener(SWT.Selection, event -> testServer());
        copy.addListener(SWT.Selection, event -> copyConfiguration());

        state = new Label(controls, SWT.WRAP);
        GridData statusData = new GridData(SWT.FILL, SWT.TOP, true, false, 4, 1);
        statusData.widthHint = DESCRIPTION_WIDTH;
        state.setLayoutData(statusData);

        Group tools = new Group(this, SWT.NONE);
        tools.setText("Инструменты для ИИ-агентов");
        tools.setLayout(new GridLayout(1, false));
        tools.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Label list = new Label(tools, SWT.NONE);
        list.setText("list_projects — проекты EDT\n"
            + "get_storage_status — состояние синхронизации\n"
            + "get_pending_changes — ожидающие изменения\n"
            + "check_repository_revision — версия хранилища 1С\n"
            + "receive_changes — автоматическое получение\n"
            + "publish_changes — автоматическое помещение\n"
            + "get_operation_status — состояние операции");
        list.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        Label warning = new Label(tools, SWT.WRAP);
        warning.setText("MCP-команды выполняются без диалогов EDT. "
            + "Получение принимает вариант хранилища и может заменить "
            + "локальные отличия. Помещение через Lock/Load/Commit "
            + "остаётся экспериментальным. Ручной режим EDT не меняется.");
        GridData warningData = new GridData(SWT.FILL, SWT.TOP, true, false);
        warningData.widthHint = DESCRIPTION_WIDTH;
        warning.setLayoutData(warningData);

        try {
            var saved = BridgeMcpSettings.load();
            portText.setText(Integer.toString(saved.port()));
            tokenText.setText(saved.token());
            autoStart.setSelection(saved.autoStart());
        } catch (StorageException | RuntimeException ex) {
            canSave = false;
            portText.setText(Integer.toString(BridgeMcpSettings.DEFAULT_PORT));
            tokenText.setText("");
            MessageDialog.openError(getShell(), "MCP",
                "Не удалось прочитать Secure Storage. "
                + "Управление сервером недоступно до восстановления доступа.");
        }
        updateEndpoint();
        autoStart.setEnabled(canSave);
        start.setEnabled(canSave);
    }

    boolean save() {
        if (!canSave) return false;
        try {
            int newPort = Integer.parseInt(portText.getText().trim());
            String newToken = tokenText.getText().trim();
            if (BridgeMcpServer.INSTANCE.isRunning()
                    && !BridgeMcpServer.INSTANCE.matches(newPort, newToken)) {
                MessageDialog.openError(getShell(), "MCP запущен",
                    "Сначала остановите MCP-сервер, затем сохраните новый "
                    + "порт или токен и запустите сервер снова.");
                return false;
            }
            BridgeMcpSettings.save(newPort, newToken, autoStart.getSelection());
            return true;
        } catch (IllegalArgumentException ex) {
            MessageDialog.openError(getShell(), "Неверные настройки MCP", ex.getMessage());
        } catch (StorageException | BackingStoreException | IOException | RuntimeException ex) {
            MessageDialog.openError(getShell(), "MCP",
                "Не удалось сохранить настройки MCP. Проверьте журнал EDT и Secure Storage.");
        }
        return false;
    }

    private void startServer() {
        if (!save()) return;
        try {
            BridgeMcpServer.INSTANCE.start(
                Integer.parseInt(portText.getText().trim()), tokenText.getText().trim());
            updateStatus();
        } catch (IOException | RuntimeException ex) {
            MessageDialog.openError(getShell(), "Не удалось запустить MCP",
                ex.getMessage() == null ? "Проверьте порт и журнал EDT." : ex.getMessage());
            updateStatus();
            setIndicator("Ошибка запуска MCP: проверьте порт и журнал EDT.",
                SWT.COLOR_DARK_RED);
        }
    }

    private void testServer() {
        test.setEnabled(false);
        setIndicator("Проверка MCP-сервера…", SWT.COLOR_DARK_YELLOW);
        Display display = getDisplay();
        Job probe = new Job("EDT Storage Bridge — проверка MCP") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    String result = BridgeMcpServer.INSTANCE.probe();
                    display.asyncExec(() -> {
                        if (!isDisposed()) {
                            updateStatus();
                            setIndicator(result, SWT.COLOR_DARK_GREEN);
                        }
                    });
                    return Status.OK_STATUS;
                } catch (IOException | RuntimeException ex) {
                    display.asyncExec(() -> {
                        if (!isDisposed()) {
                            updateStatus();
                            setIndicator("Ошибка проверки MCP: " +
                                (ex instanceof IllegalStateException
                                    ? "сервер остановлен" : "ошибка HTTP/MCP; см. Error Log"),
                                SWT.COLOR_DARK_RED);
                        }
                    });
                    return new Status(IStatus.WARNING, BridgeActivator.PLUGIN_ID,
                        "MCP self-test failed (" + ex.getClass().getSimpleName() + ")");
                } finally {
                    display.asyncExec(() -> {
                        if (!isDisposed()) test.setEnabled(BridgeMcpServer.INSTANCE.isRunning());
                    });
                }
            }
        };
        probe.setUser(true);
        probe.schedule();
    }

    private void copyConfiguration() {
        if (!save()) return;
        int port = Integer.parseInt(portText.getText().trim());
        String token = tokenText.getText().trim();
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("type", "http");
        client.put("url", BridgeMcpSettings.url(port));
        if (!token.isEmpty()) {
            client.put("headers", Map.of("Authorization", "Bearer " + token));
        }
        String config = BridgeMcpJson.stringify(
            Map.of("mcpServers", Map.of("edt-storage-bridge", client)));
        Clipboard clipboard = new Clipboard(getDisplay());
        try {
            clipboard.setContents(new Object[] { config },
                new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
        MessageDialog.openInformation(getShell(), "Конфигурация скопирована",
            token.isEmpty()
                ? "JSON для MCP-клиента скопирован. Для локального "
                    + "подключения без токена достаточно адреса сервера."
                : "JSON для MCP-клиента скопирован в буфер обмена. "
                    + "Он содержит секретный токен доступа. "
                    + "Не публикуйте его в Git или чатах.");
    }

    private void updateEndpoint() {
        if (endpoint == null || endpoint.isDisposed()) return;
        String port = portText.getText().trim();
        endpoint.setText("http://127.0.0.1:" + port + "/mcp");
        endpoint.getParent().layout();
    }

    private void updateStatus() {
        if (state == null || state.isDisposed()) return;
        boolean running = BridgeMcpServer.INSTANCE.isRunning();
        start.setEnabled(canSave && !running);
        stop.setEnabled(running);
        test.setEnabled(running);
        if (running) {
            setIndicator("MCP запущен: "
                + BridgeMcpSettings.url(BridgeMcpServer.INSTANCE.port())
                + (BridgeMcpServer.INSTANCE.requiresAuthentication()
                    ? " (по токену)" : " (без токена)"),
                SWT.COLOR_DARK_GREEN);
        } else {
            setIndicator("MCP не запущен" + (autoStart.getSelection()
                ? " (автозапуск при следующем старте EDT)"
                : " (автозапуск отключён)"),
                SWT.COLOR_DARK_GRAY);
        }
    }

    private void setIndicator(String message, int swtColor) {
        if (state == null || state.isDisposed()) return;
        state.setText("● " + message);
        state.setForeground(getDisplay().getSystemColor(swtColor));
        state.getParent().layout();
    }
}
