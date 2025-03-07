package com.example;

import io.grpc.ManagedChannel;          //Для связи с серверои
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import javax.swing.*;         //Интерфейс
import java.awt.*;
import java.util.Arrays;   //По мелочи
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TicTacToeSwingClient extends JFrame {
    private ManagedChannel channel; //Связзь с манагером
    private com.example.tictactoe.TicTacToeGrpc.TicTacToeBlockingStub blockingStub; //Связь с сервером
    private com.example.tictactoe.TicTacToeGrpc.TicTacToeStub asyncStub;
    private String playerName;   //Инфа о клиенте
    private String currentGameId;
    private String playerSymbol;

    private JPanel mainPanel; //Интерфей
    private CardLayout cardLayout;
    private DefaultListModel<RoomInfoWrapper> listModel;
    private JList<RoomInfoWrapper> roomsList;
    private JButton[][] gridButtons = new JButton[3][3];
    private JLabel statusLabel;
    private JLabel playerSymbolLabel;

    private static class RoomInfoWrapper {    //Для списка названий комнат, чтобы выводить их корректно
        private final com.example.tictactoe.RoomInfo info;

        public RoomInfoWrapper(com.example.tictactoe.RoomInfo info) {
            this.info = info;      //Прилетает от сервера
        }

        @Override
        public String toString() {    //Чтобы отображался список нормально
            return String.format("%s (%d/2) - %s",
                    info.getRoomName(),
                    info.getPlayersCount(),
                    info.getStatus());
        }

        public String getRoomId() {
            return info.getRoomId();
        }
    }

    public static void main(String[] args) {  //Стартуем
        SwingUtilities.invokeLater(() -> new TicTacToeSwingClient().initialize());
    }

    private void initialize() {
        setupConnection(); //Связываемся с  сервером и создаём интерфейсы
        setupGUI();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                shutdown();
            }
        });
    }

    private void setupConnection() {       //Связываемся с сервером
        channel = ManagedChannelBuilder.forAddress("localhost", 50051)
                .usePlaintext()
                .build();
        blockingStub = com.example.tictactoe.TicTacToeGrpc.newBlockingStub(channel);
        asyncStub = com.example.tictactoe.TicTacToeGrpc.newStub(channel);
    }

    private void setupGUI() {      //Создаём все основные интерфейсы
        setTitle("Крестики-Нолики");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(600, 400);
        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);

        createLoginPanel();
        createMainPanel();
        createGamePanel();

        add(mainPanel);
        setVisible(true);
    }

    private void createLoginPanel() {
        JPanel panel = new JPanel(new GridLayout(3, 1, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JTextField nameField = new JTextField();
        JButton loginButton = new JButton("Войти");

        loginButton.addActionListener(e -> {   //Если игрок ввёл логин, то входим
            playerName = nameField.getText();
            if (!playerName.isEmpty()) {
                cardLayout.show(mainPanel, "main");
                refreshRooms(); //Обновляет комнаты в списке
            }
        });

        panel.add(new JLabel("Введите ваше имя:"));
        panel.add(nameField);
        panel.add(loginButton);

        mainPanel.add(panel, "login");
    }

    private void createMainPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        listModel = new DefaultListModel<>();
        roomsList = new JList<>(listModel);

        JButton createButton = new JButton("Создать комнату");
        JButton refreshButton = new JButton("Обновить список");
        JButton joinButton = new JButton("Присоединиться");

        createButton.addActionListener(e -> createRoom());
        refreshButton.addActionListener(e -> refreshRooms());
        joinButton.addActionListener(e -> joinSelectedRoom());

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 10, 10));
        buttonPanel.add(createButton);
        buttonPanel.add(refreshButton);

        panel.add(new JScrollPane(roomsList), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        panel.add(joinButton, BorderLayout.EAST);

        mainPanel.add(panel, "main");
    }

    private void createGamePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JPanel gridPanel = new JPanel(new GridLayout(3, 3, 5, 5));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                JButton button = new JButton();
                button.setFont(new Font("Arial", Font.BOLD, 60));
                button.setFocusPainted(false);
                button.setBackground(Color.WHITE);
                int position = row * 3 + col;
                button.addActionListener(e -> makeMove(position));
                gridButtons[row][col] = button;
                gridPanel.add(button);
            }
        }

        statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Arial", Font.BOLD, 18));
        statusLabel.setForeground(Color.DARK_GRAY);

        JPanel infoPanel = new JPanel(new FlowLayout());
        playerSymbolLabel = new JLabel("Ваш символ: ");
        infoPanel.add(playerSymbolLabel);

        JButton exitButton = new JButton("Выйти из игры");
        exitButton.addActionListener(e -> leaveGame());
        JPanel controlPanel = new JPanel(new BorderLayout());
        controlPanel.add(infoPanel, BorderLayout.NORTH);
        controlPanel.add(exitButton, BorderLayout.SOUTH);

        panel.add(controlPanel, BorderLayout.NORTH);
        panel.add(gridPanel, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);

        mainPanel.add(panel, "game");
    }

    private void createRoom() {   //Если игрок ввёл имя комнаты, то заходим в эту комнату и отправляем инфу серверу об этом
        String roomName = JOptionPane.showInputDialog(this, "Введите название комнаты:");
        if (roomName == null || roomName.isEmpty()) return;

        new Thread(() -> {
            try {
                com.example.tictactoe.RoomResponse response = blockingStub.createRoom(
                        com.example.tictactoe.CreateRoomRequest.newBuilder()
                                .setRoomName(roomName)
                                .build());

                if (response.getSuccess()) {
                    currentGameId = response.getRoomId();
                    SwingUtilities.invokeLater(() -> {
                        cardLayout.show(mainPanel, "game");
                        joinGame();
                    });
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, "Ошибка создания комнаты: " + e.getMessage())
                );
            }
        }).start();    //Пускаем
    }

    private void refreshRooms(){ //Получить список комнат
        new Thread(() -> {
            try {
                com.example.tictactoe.RoomList roomList = blockingStub.listRooms(com.example.tictactoe.Empty.getDefaultInstance());
                SwingUtilities.invokeLater(() -> {
                    listModel.clear();
                    roomList.getRoomsList().forEach(room -> {
                        if (room.getStatus().equals("WAITING") && room.getPlayersCount() == 1) {
                            listModel.addElement(new RoomInfoWrapper(room)); //Добавляем новую комнату через обработчик
                        }
                    });
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, "Ошибка обновления списка: " + e.getMessage())
                );
            }
        }).start();
    }

    private void joinSelectedRoom() {   //Если комната выбрана, подключаемся
        RoomInfoWrapper selected = roomsList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Выберите комнату!");
            return;
        }
        currentGameId = selected.getRoomId();
        joinGame();
    }

    private void joinGame() {   //Онуляем комнатту и отправляем инфу о подключении
        resetGameUI();
        com.example.tictactoe.JoinRoomRequest joinRequest = com.example.tictactoe.JoinRoomRequest.newBuilder()
                .setRoomId(currentGameId)
                .setPlayerName(playerName)
                .build();

        asyncStub.joinRoom(joinRequest, new GameStateObserver());
        cardLayout.show(mainPanel, "game"); //Меняем на поле игровое
    }

    private void resetGameUI() {       //Все поля пустые и ожидаем игрока
        Arrays.stream(gridButtons).flatMap(Arrays::stream).forEach(btn -> {
            btn.setText("");
            btn.setEnabled(false);
        });
        statusLabel.setText("Подключение к игре...");
        playerSymbolLabel.setText("Ваш символ: ");
        playerSymbol = null;
    }

    private void makeMove(int position) {      //Если есть куда ходить, сообщаем о ходе серверу
        if (position < 0 || position >= 9) return;

        new Thread(() -> {
            try {
                com.example.tictactoe.MoveResult result = blockingStub.makeMove(com.example.tictactoe.Move.newBuilder()
                        .setGameId(currentGameId)
                        .setPlayerName(playerName)
                        .setPosition(position)
                        .build());

                SwingUtilities.invokeLater(() -> {
                    if (!result.getSuccess()) {
                        String errorMessage = switch (result.getMessage()) {
                            case "Invalid position" -> "Некорректная позиция!";
                            case "Cell is occupied" -> "Клетка занята!";
                            case "Wrong turn" -> "Сейчас не ваш ход!";
                            default -> "Ошибка хода!";
                        };
                        JOptionPane.showMessageDialog(
                                this,
                                errorMessage,
                                "Ошибка",
                                JOptionPane.WARNING_MESSAGE
                        );
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(
                                this,
                                "Ошибка соединения: " + e.getMessage(),
                                "Ошибка",
                                JOptionPane.ERROR_MESSAGE
                        )
                );
            }
        }).start();
    }

    private void leaveGame() {      //Выходим из комнаты и обновляем список комнат
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Вы уверены, что хотите выйти?",
                "Подтверждение выхода",
                JOptionPane.YES_NO_OPTION
        );

        if (choice == JOptionPane.YES_OPTION) {
            blockingStub.leaveRoom(com.example.tictactoe.LeaveRequest.newBuilder()
                    .setRoomId(currentGameId)
                    .setPlayerName(playerName)
                    .build());
            cardLayout.show(mainPanel, "main");
            refreshRooms();
        }
    }
    private void updateSymbol(com.example.tictactoe.GameState state) {   //Обновляем символ и интерфейс его
        playerSymbol = state.getPlayerSymbol();
        playerSymbolLabel.setText("Ваш символ: " + playerSymbol);
        playerSymbolLabel.setForeground(
                playerSymbol.equals("X") ? new Color(0, 100, 255) : new Color(255, 50, 50)
        );
    }
    private class GameStateObserver implements StreamObserver<com.example.tictactoe.GameState> {
        @Override
        public void onNext(com.example.tictactoe.GameState state) {  //Принимаем от сервера инфу и обновляем статус игры, поля, интерфейса
            SwingUtilities.invokeLater(() -> {
                handleStatusUpdate(state);
                if (!(state.getStatus().contains("Соперник"))) updateBoard(state.getBoardList());
                updateUI(state);
            });
        }

        private void handleStatusUpdate(com.example.tictactoe.GameState state) {
            String status = state.getStatus();

            updateSymbol(state);

            if (status.contains("Победил") || status.equals("Ничья!")) {  //Проверем ситуацию в игре с сервера
                JOptionPane.showMessageDialog(
                        TicTacToeSwingClient.this,
                        status,
                        "Игра завершена",
                        JOptionPane.INFORMATION_MESSAGE
                );
            } else if (status.equals("Соперник покинул игру")) {  //Оставшемуся игроку предлагается ливнуть или продолжать ждать игру с новым символом
                int choice = JOptionPane.showConfirmDialog(
                        TicTacToeSwingClient.this,
                        "Соперник вышел. Вернуться в лобби?",
                        "Игра прервана",
                        JOptionPane.YES_NO_OPTION
                );
                if (choice == JOptionPane.YES_OPTION) leaveGame();
                else if (choice == JOptionPane.NO_OPTION){
                    resetGameUI();
                    updateSymbol(state);
                }
            }

            statusLabel.setText(status);
            statusLabel.setForeground(getStatusColor(status));
        }

        private Color getStatusColor(String status) {     //Раскрашивает буковки
            if (status.contains("Победил")) return new Color(0, 150, 0);
            if (status.equals("Ничья!")) return Color.ORANGE;
            if (status.equals("Соперник покинул игру")) return Color.RED;
            return Color.DARK_GRAY;
        }

        private void updateBoard(List<String> board) {    //Обновляет поле

            for (int i = 0; i < 9; i++) {
                int row = i / 3;
                int col = i % 3;
                String symbol = board.get(i);
                JButton btn = gridButtons[row][col];

                btn.setText(symbol.isEmpty() ? "" : symbol);
                btn.setForeground(
                        symbol.equals("X") ?
                                new Color(0, 100, 255) :
                                new Color(255, 50, 50)
                );
            }
        }

        private void updateUI(com.example.tictactoe.GameState state) {      //Обновляем интерфейс и позволяем жать на кнопки, если они не заняты, или игра активна
            boolean isMyTurn = state.getCurrentPlayer().equals(playerSymbol);
            boolean isGameActive = state.getStatus().startsWith("Сейчас ходит:");

            for (JButton[] row : gridButtons) {
                for (JButton btn : row) {
                    btn.setEnabled(isGameActive && isMyTurn && btn.getText().isEmpty());
                }
            }
        }

        @Override
        public void onError(Throwable t) {        //Вдруг если что вот да
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(
                        TicTacToeSwingClient.this,
                        "Ошибка соединения: " + t.getMessage(),
                        "Ошибка",
                        JOptionPane.ERROR_MESSAGE
                );
                cardLayout.show(mainPanel, "main");
            });
        }

        @Override
        public void onCompleted() {
            SwingUtilities.invokeLater(() -> {
                cardLayout.show(mainPanel, "main");
                refreshRooms();
            });
        }
    }

    private void shutdown() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}