package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import org.eclipse.swt.widgets.Display;
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
    private Text portText;
    private Text tokenText;
    private Label state;
    private Label endpoint;
    private Button start;
    private Button stop;
    private Button test;
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
        settings.setLayout(new GridLayout(3, false));
        settings.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(settings, SWT.NONE).setText("Адрес:");
        endpoint = new Label(settings, SWT.NONE);
        endpoint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(settings, SWT.NONE).setText("Порт:");
        portText = new Text(settings, SWT.BORDER);
        portText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(settings, SWT.NONE).setText("Только 127.0.0.1");
        portText.addModifyListener(event -> updateEndpoint());

        new Label(settings, SWT.NONE).setText("Токен доступа:");
        tokenText = new Text(settings, SWT.BORDER | SWT.PASSWORD);
        tokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
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

        new Label(settings, SWT.NONE).setText("");
        Button regenerate = new Button(settings, SWT.PUSH);
        regenerate.setText("Создать новый токен");
        regenerate.addListener(SWT.Selection, event -> {
            if (BridgeMcpServer.INSTANCE.isRunning()
                    && !MessageDialog.openConfirm(getShell(), "Новый токен MCP",
                        "После смены токена нужно остановить и запустить "
                        + "MCP-сервер, а также обновить настройки MCP-клиентов. "
                        + "Продолжить?")) return;
            tokenText.setText(BridgeMcpSettings.newToken());
        });
        new Label(settings, SWT.NONE).setText("");

        Label scope = new Label(settings, SWT.WRAP);
        scope.setText("Сервер общий для рабочей области EDT. MCP-клиент "
            + "передаёт имя проекта каждому инструменту. "
            + "Доступ только с этого компьютера, по токену. "
            + "Порт не должен совпадать с портом другого MCP-сервера.");
        scope.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));

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
        state.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 4, 1));

        Group tools = new Group(this, SWT.NONE);
        tools.setText("Инструменты для ИИ-агентов");
        tools.setLayout(new GridLayout(1, false));
        tools.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Label list = new Label(tools, SWT.WRAP);
        list.setText("list_projects — открытые проекты\n"
            + "get_storage_status — состояние версий и синхронизации\n"
            + "get_pending_changes — необработанные объекты\n"
            + "check_repository_revision — актуальная версия в 1С\n"
            + "request_receive_changes — запросить выборочное получение\n"
            + "request_full_review — запросить полную сверку\n"
            + "request_publish_changes — запросить помещение через EDT");
        list.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Label warning = new Label(tools, SWT.WRAP);
        warning.setText("Запросы действий требуют подтверждения в EDT. "
            + "MCP не выполняет прямой Commit. После подтверждения в EDT "
            + "существующий сценарий помещения может записать изменения "
            + "в хранилище. Алгоритмы обмена не изменяются.");
        warning.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridData warningData = (GridData) warning.getLayoutData();
        warningData.widthHint = 580;

        try {
            var saved = BridgeMcpSettings.load();
            portText.setText(Integer.toString(saved.port()));
            tokenText.setText(saved.token().isBlank()
                ? BridgeMcpSettings.newToken() : saved.token());
        } catch (StorageException | RuntimeException ex) {
            canSave = false;
            portText.setText(Integer.toString(BridgeMcpSettings.DEFAULT_PORT));
            tokenText.setText("");
            MessageDialog.openError(getShell(), "MCP",
                "Не удалось прочитать Secure Storage. "
                + "Управление сервером недоступно до восстановления доступа.");
        }
        updateEndpoint();
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
            BridgeMcpSettings.save(newPort, newToken);
            return true;
        } catch (IllegalArgumentException ex) {
            MessageDialog.openError(getShell(), "Неверные настройки MCP", ex.getMessage());
        } catch (StorageException | BackingStoreException | IOException | RuntimeException ex) {
            MessageDialog.openError(getShell(), "MCP",
                "Не удалось сохранить токен в защищённом хранилище EDT.");
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
        }
    }

    private void testServer() {
        test.setEnabled(false);
        state.setText("Проверка HTTP и MCP initialize / tools/list…");
        Display display = getDisplay();
        Job probe = new Job("EDT Storage Bridge — проверка MCP") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    String result = BridgeMcpServer.INSTANCE.probe();
                    display.asyncExec(() -> {
                        if (!isDisposed()) {
                            updateStatus();
                            state.setText(result);
                        }
                    });
                    return Status.OK_STATUS;
                } catch (IOException | RuntimeException ex) {
                    display.asyncExec(() -> {
                        if (!isDisposed()) {
                            updateStatus();
                            state.setText("Проверка не пройдена: " +
                                (ex instanceof IllegalStateException
                                    ? "сервер остановлен" : "ошибка HTTP/MCP"));
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
        String config = BridgeMcpJson.stringify(Map.of("mcpServers", Map.of(
            "edt-storage-bridge", Map.of(
                "type", "http",
                "url", BridgeMcpSettings.url(port),
                "headers", Map.of("Authorization", "Bearer " + token)))));
        Clipboard clipboard = new Clipboard(getDisplay());
        try {
            clipboard.setContents(new Object[] { config },
                new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
        MessageDialog.openInformation(getShell(), "Конфигурация скопирована",
            "JSON для MCP-клиента скопирован в буфер обмена. "
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
        state.setText(running
            ? "MCP запущен: " + BridgeMcpSettings.url(BridgeMcpServer.INSTANCE.port())
                + " (локально, с авторизацией)"
            : "MCP остановлен. Автоматический запуск при старте EDT отключён.");
        state.getParent().layout();
    }
}
