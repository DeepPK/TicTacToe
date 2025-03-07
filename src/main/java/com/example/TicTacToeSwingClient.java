package com.example;

import com.example.tictactoe.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TicTacToeSwingClient extends JFrame {
    private ManagedChannel channel;
    private TicTacToeGrpc.TicTacToeBlockingStub blockingStub;
    private TicTacToeGrpc.TicTacToeStub asyncStub;
    private String playerName;
    private String currentGameId;
    private String playerSymbol;
    private String lastKnownStatus = "WAITING";

    private JPanel mainPanel;
    private CardLayout cardLayout;
    private DefaultListModel<RoomInfoWrapper> listModel;
    private JList<RoomInfoWrapper> roomsList;
    private JButton[][] gridButtons = new JButton[3][3];
    private JLabel statusLabel;
    private JLabel playerSymbolLabel;

    private static class RoomInfoWrapper {
        private final RoomInfo info;

        public RoomInfoWrapper(RoomInfo info) {
            this.info = info;
        }

        @Override
        public String toString() {
            return String.format("%s (%d/2) - %s",
                    info.getRoomName(),
                    info.getPlayersCount(),
                    info.getStatus());
        }

        public String getRoomId() {
            return info.getRoomId();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TicTacToeSwingClient().initialize());
    }

    private void initialize() {
        setupConnection();
        setupGUI();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                shutdown();
            }
        });
    }

    private void setupConnection() {
        channel = ManagedChannelBuilder.forAddress("localhost", 50051)
                .usePlaintext()
                .build();
        blockingStub = TicTacToeGrpc.newBlockingStub(channel);
        asyncStub = TicTacToeGrpc.newStub(channel);
    }

    private void setupGUI() {
        setTitle("Tic Tac Toe");
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
        JButton loginButton = new JButton("Login");

        loginButton.addActionListener(e -> {
            playerName = nameField.getText();
            if (!playerName.isEmpty()) {
                cardLayout.show(mainPanel, "main");
                refreshRooms();
            }
        });

        panel.add(new JLabel("Enter your name:"));
        panel.add(nameField);
        panel.add(loginButton);

        mainPanel.add(panel, "login");
    }

    private void createMainPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        listModel = new DefaultListModel<>();
        roomsList = new JList<>(listModel);

        JButton createButton = new JButton("Create Room");
        JButton refreshButton = new JButton("Refresh Rooms");
        JButton joinButton = new JButton("Join Room");

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

        JButton exitButton = new JButton("Покинуть игру");
        exitButton.addActionListener(e -> leaveGame());
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        controlPanel.add(exitButton);

        panel.add(infoPanel, BorderLayout.NORTH);
        panel.add(gridPanel, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        panel.add(controlPanel, BorderLayout.SOUTH);

        mainPanel.add(panel, "game");
    }

    private void createRoom() {
        String roomName = JOptionPane.showInputDialog(this, "Введите название комнаты:");
        if (roomName == null || roomName.isEmpty()) return;

        new Thread(() -> {
            try {
                RoomResponse response = blockingStub.createRoom(
                        CreateRoomRequest.newBuilder()
                                .setRoomName(roomName)
                                .build());

                if (response.getSuccess()) {
                    currentGameId = response.getRoomId();
                    SwingUtilities.invokeLater(() -> {
                        cardLayout.show(mainPanel, "game");
                        statusLabel.setText("Ожидание второго игрока...");
                        joinGame();
                    });
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, "Ошибка создания комнаты: " + e.getMessage())
                );
            }
        }).start();
    }

    private void refreshRooms() {
        new Thread(() -> {
            try {
                RoomList roomList = blockingStub.listRooms(Empty.getDefaultInstance());
                SwingUtilities.invokeLater(() -> {
                    listModel.clear();
                    roomList.getRoomsList().forEach(room -> {
                        // Фильтрация невалидных комнат на клиенте
                        if (room.getStatus().equals("WAITING") && room.getPlayersCount() == 1) {
                            listModel.addElement(new RoomInfoWrapper(room));
                        }
                    });
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, "Ошибка обновления: " + e.getMessage()));
            }
        }).start();
    }

    private void joinSelectedRoom() {
        RoomInfoWrapper selected = roomsList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Выберите комнату!");
            return;
        }
        currentGameId = selected.getRoomId();
        joinGame();
    }

    private void makeMove(int position) {
        if (position < 0 || position >= 9) return;

        new Thread(() -> {
            try {
                MoveResult result = blockingStub.makeMove(Move.newBuilder()
                        .setGameId(currentGameId)
                        .setPlayerName(playerName)
                        .setPosition(position)
                        .build());

                SwingUtilities.invokeLater(() -> {
                    if (!result.getSuccess()) {
                        String errorMessage = switch (result.getMessage()) {
                            case "Invalid position" -> "Некорректная позиция!";
                            case "Cell is occupied" -> "Клетка занята!";
                            case "Wrong turn" -> "Не ваш ход!";
                            default -> "Ошибка хода!";
                        };
                        JOptionPane.showMessageDialog(
                                TicTacToeSwingClient.this,
                                errorMessage,
                                "Ошибка",
                                JOptionPane.WARNING_MESSAGE
                        );
                    } else {
                        // После успешного хода обновляем состояние кнопок
                        for (JButton[] row : gridButtons) {
                            for (JButton btn : row) {
                                btn.setEnabled(false);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(
                                TicTacToeSwingClient.this,
                                "Ошибка соединения: " + e.getMessage(),
                                "Ошибка",
                                JOptionPane.ERROR_MESSAGE
                        )
                );
            }
        }).start();
    }

    private void leaveGame() {
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Вы уверены, что хотите выйти?",
                "Подтверждение выхода",
                JOptionPane.YES_NO_OPTION
        );

        if (choice == JOptionPane.YES_OPTION) {
            blockingStub.leaveRoom(LeaveRequest.newBuilder()
                    .setRoomId(currentGameId)
                    .setPlayerName(playerName)
                    .build());
            cardLayout.show(mainPanel, "main");
            refreshRooms();
        }
    }
    private void joinGame() {
        // Сброс предыдущего состояния перед подключением
        resetGameUI();

        JoinRoomRequest joinRequest = JoinRoomRequest.newBuilder()
                .setRoomId(currentGameId)
                .setPlayerName(playerName)
                .build();

        asyncStub.joinRoom(joinRequest, new GameStateObserver());
        cardLayout.show(mainPanel, "game");
    }

    private void resetGameUI() {
        Arrays.stream(gridButtons).flatMap(Arrays::stream).forEach(btn -> {
            btn.setText("");
            btn.setEnabled(false);
        });
        statusLabel.setText(" ");
        playerSymbolLabel.setText("Ваш символ: ");
        playerSymbol = null;
    }

    private class GameStateObserver implements StreamObserver<GameState> {
        @Override
        public void onNext(GameState state) {
            SwingUtilities.invokeLater(() -> {
                // Полный сброс интерфейса при новом подключении
                if (state.getStatus().equals("WAITING") && state.getPlayersCount() == 1) {
                    resetGameUI();
                }

                // Обновление поля только если игра активна
                if (state.getStatus().equals("IN_PROGRESS") ||
                        state.getStatus().endsWith("_WON") ||
                        state.getStatus().equals("DRAW")) {
                    updateBoard(state.getBoardList());
                }

                lastKnownStatus = state.getStatus();

                // Обновление информации о количестве игроков
                if (state.getPlayersCount() == 1) {
                    statusLabel.setText("Ожидание второго игрока (1/2)");
                    statusLabel.setForeground(Color.DARK_GRAY);
                } else if (state.getPlayersCount() == 2) {
                    statusLabel.setText("Игра началась!");
                    statusLabel.setForeground(Color.DARK_GRAY);
                }

                // Обновление символа игрока
                if (playerSymbol == null && !state.getPlayerSymbol().isEmpty()) {
                    playerSymbol = state.getPlayerSymbol();
                    playerSymbolLabel.setText("Ваш символ: " + playerSymbol);
                    playerSymbolLabel.setForeground(
                            playerSymbol.equals("X") ? new Color(0, 100, 255) : new Color(255, 50, 50)
                    );
                }

                // Обработка статусов игры
                switch (state.getStatus()) {
                    case "IN_PROGRESS":
                        statusLabel.setText("Сейчас ходит: " + state.getCurrentPlayer());
                        statusLabel.setForeground(Color.DARK_GRAY);
                        break;
                    case "X_WON":
                    case "O_WON":
                        String winner = state.getStatus().substring(0, 1);
                        statusLabel.setText("Победил " + winner + "!");
                        statusLabel.setForeground(new Color(0, 150, 0));
                        break;
                    case "DRAW":
                        statusLabel.setText("Ничья!");
                        statusLabel.setForeground(Color.ORANGE);
                        break;
                    case "ABANDONED":
                        statusLabel.setText("Соперник вышел из игры");
                        statusLabel.setForeground(Color.RED);
                        break;
                }

                // Обновление игрового поля
                updateBoard(state.getBoardList());

                // Управление активностью кнопок
                boolean gameActive = "IN_PROGRESS".equals(state.getStatus());
                boolean myTurn = state.getCurrentPlayer().equals(playerSymbol);

                for (JButton[] row : gridButtons) {
                    for (JButton btn : row) {
                        boolean cellEmpty = btn.getText().isEmpty();
                        btn.setEnabled(gameActive && myTurn && cellEmpty);
                    }
                }
            });
        }

        private void resetGameUI() {
            for (JButton[] row : gridButtons) {
                for (JButton btn : row) {
                    btn.setText("");
                    btn.setEnabled(false);
                }
            }
            statusLabel.setText("Ожидание второго игрока...");
            playerSymbolLabel.setText("Ваш символ: ");
            playerSymbol = null;
        }

        private void updateBoard(List<String> board) {
            for (int i = 0; i < 9; i++) {
                int row = i / 3;
                int col = i % 3;
                String symbol = board.get(i);
                JButton btn = gridButtons[row][col];

                btn.setText(symbol.isEmpty() ? "" : symbol);
                btn.setForeground(
                        symbol.equals("X") ? new Color(0, 100, 255) : new Color(255, 50, 50)
                );
            }
        }

        @Override
        public void onError(Throwable t) {
            SwingUtilities.invokeLater(() -> {
                if (!"WAITING".equals(lastKnownStatus)) {
                    JOptionPane.showMessageDialog(
                            TicTacToeSwingClient.this,
                            "Соединение потеряно: " + t.getMessage(),
                            "Ошибка",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
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