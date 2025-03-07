package com.example;

import com.example.tictactoe.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TicTacToeSwingClient extends JFrame {
    private ManagedChannel channel;
    private TicTacToeGrpc.TicTacToeBlockingStub blockingStub;
    private TicTacToeGrpc.TicTacToeStub asyncStub;
    private String playerName;
    private String currentGameId;

    // GUI Components
    private JPanel mainPanel;
    private CardLayout cardLayout;
    private DefaultListModel<String> listModel;
    private JList<String> roomsList;
    private JButton[][] gridButtons = new JButton[3][3];
    private JLabel statusLabel;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TicTacToeSwingClient().initialize());
    }

    private void initialize() {
        setupConnection();
        setupGUI();
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
                button.setFont(new Font("Arial", Font.BOLD, 40));
                int position = row * 3 + col;
                button.addActionListener(e -> makeMove(position));
                gridButtons[row][col] = button;
                gridPanel.add(button);
            }
        }

        statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Arial", Font.BOLD, 16));
        statusLabel.setForeground(Color.DARK_GRAY);

        panel.add(gridPanel, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);

        mainPanel.add(panel, "game");
    }

    private void createRoom() {
        String roomName = JOptionPane.showInputDialog(this, "Enter room name:");
        if (roomName == null || roomName.isEmpty()) return;

        RoomResponse response = blockingStub.createRoom(
                RoomRequest.newBuilder()
                        .setRoomName(roomName)
                        .setPlayerName(playerName)
                        .build());

        if (response.getSuccess()) {
            currentGameId = response.getRoomId();
            joinGame();
        }
    }

    private void refreshRooms() {
        new Thread(() -> {
            try {
                RoomList roomList = blockingStub.listRooms(Empty.getDefaultInstance());
                SwingUtilities.invokeLater(() -> {
                    listModel.clear();
                    roomList.getRoomsList().forEach(room ->
                            listModel.addElement(String.format("%s: %s (%d/2)",
                                    room.getRoomId(),
                                    room.getRoomName(),
                                    room.getPlayersCount()))
                    );
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, "Error: " + e.getMessage()));
            }
        }).start();
    }

    private void joinSelectedRoom() {
        int selectedIndex = roomsList.getSelectedIndex();
        if (selectedIndex == -1) {
            JOptionPane.showMessageDialog(this, "Select a room first!");
            return;
        }

        String selected = listModel.getElementAt(selectedIndex);
        String[] parts = selected.split(":");
        if (parts.length < 1) {
            JOptionPane.showMessageDialog(this, "Invalid room format!");
            return;
        }

        currentGameId = parts[0].trim();
        joinGame();
    }

    private void joinGame() {
        asyncStub.joinRoom(RoomRequest.newBuilder()
                .setRoomName(currentGameId)
                .setPlayerName(playerName)
                .build(), new GameStateObserver());

        cardLayout.show(mainPanel, "game");
    }

    private void makeMove(int position) {
        if (position < 0 || position >= 9) {
            JOptionPane.showMessageDialog(this, "Invalid position!");
            return;
        }
        new Thread(() -> {
            try {
                MoveResult result = blockingStub.makeMove(Move.newBuilder()
                        .setGameId(currentGameId)
                        .setPlayerName(playerName)
                        .setPosition(position)
                        .build());

                if (!result.getSuccess()) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(
                                    this,
                                    "Error: " + result.getMessage(),
                                    "Invalid Move",
                                    JOptionPane.WARNING_MESSAGE
                            )
                    );
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(
                                this,
                                "Connection error: " + e.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                        )
                );
            }
        }).start();
    }

    private class GameStateObserver implements StreamObserver<GameState> {
        @Override
        public void onNext(GameState state) {
            SwingUtilities.invokeLater(() -> {
                // Обновляем статус
                String statusText;
                switch (state.getStatus()) {
                    case "X_WON":
                        statusText = "✖ Wins!";
                        break;
                    case "O_WON":
                        statusText = "○ Wins!";
                        break;
                    case "DRAW":
                        statusText = "Draw!";
                        break;
                    case "IN_PROGRESS":
                        statusText = "Current turn: " + state.getCurrentPlayer();
                        break;
                    default:
                        statusText = "Waiting for players...";
                }
                statusLabel.setText(statusText);
                statusLabel.setForeground(Color.DARK_GRAY);

                // Обновляем доску
                updateBoard(state.getBoardList());

                // Активируем кнопки только для текущего игрока
                boolean myTurn = state.getCurrentPlayer().equals(playerName);
                boolean gameActive = state.getStatus().equals("IN_PROGRESS");

                for (JButton[] row : gridButtons) {
                    for (JButton btn : row) {
                        btn.setEnabled(gameActive && myTurn && btn.getText().isEmpty());
                    }
                }
            });
        }

        @Override
        public void onError(Throwable t) {
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(TicTacToeSwingClient.this,
                            "Connection error: " + t.getMessage()));
        }

        @Override
        public void onCompleted() {
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(TicTacToeSwingClient.this, "Game finished"));
        }

        private void updateBoard(List<String> board) {
            if (board.size() != 9) return;

            for (int i = 0; i < 9; i++) {
                int row = i / 3;
                int col = i % 3;
                String symbol = board.get(i);
                JButton button = gridButtons[row][col];

                // Устанавливаем символ и цвет
                button.setText(symbol);
                if (symbol.equals("X")) {
                    button.setForeground(new Color(0, 100, 255)); // Синий
                    button.setFont(new Font("Arial", Font.BOLD, 60));
                } else if (symbol.equals("O")) {
                    button.setForeground(new Color(255, 50, 50)); // Красный
                    button.setFont(new Font("Arial", Font.BOLD, 60));
                } else {
                    button.setText("");
                }

                // Блокируем занятые ячейки
                button.setEnabled(symbol.isEmpty());
            }
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