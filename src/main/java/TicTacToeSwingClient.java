import com.example.tictactoe.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TicTacToeSwingClient {
    private ManagedChannel channel;
    private TicTacToeGrpc.TicTacToeBlockingStub blockingStub;
    private TicTacToeGrpc.TicTacToeStub asyncStub;

    private String playerName;
    private String currentGameId;
    private String playerSymbol;

    // GUI Components
    private JFrame frame;
    private JPanel mainPanel;
    private CardLayout cardLayout;
    private JList<String> roomsList;
    private DefaultListModel<String> listModel;
    private JButton[][] gridButtons = new JButton[3][3];

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TicTacToeSwingClient().initialize());
    }

    private void initialize() {
        setupConnection();
        createGUI();
    }

    private void setupConnection() {
        channel = ManagedChannelBuilder.forAddress("localhost", 50051)
                .usePlaintext()
                .build();
        blockingStub = TicTacToeGrpc.newBlockingStub(channel);
        asyncStub = TicTacToeGrpc.newStub(channel);
    }

    private void createGUI() {
        frame = new JFrame("Tic Tac Toe");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);

        createLoginPanel();
        createMainPanel();
        createGamePanel();

        frame.add(mainPanel);
        frame.setVisible(true);
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
        JButton refreshButton = new JButton("Refresh");
        JButton joinButton = new JButton("Join");

        createButton.addActionListener(e -> createRoom());
        refreshButton.addActionListener(e -> refreshRooms());
        joinButton.addActionListener(e -> joinRoom());

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
                button.setFont(new Font("Arial", Font.BOLD, 40));
                int finalRow = row;
                int finalCol = col;
                button.addActionListener(e -> makeMove(finalRow * 3 + finalCol));
                gridButtons[row][col] = button;
                gridPanel.add(button);
            }
        }

        JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Arial", Font.PLAIN, 16));

        panel.add(gridPanel, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);

        mainPanel.add(panel, "game");
    }

    private void createRoom() {
        String roomName = JOptionPane.showInputDialog(frame, "Enter room name:");
        if (roomName != null && !roomName.isEmpty()) {
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
    }

    private void refreshRooms() {
        new Thread(() -> {
            RoomList roomList = blockingStub.listRooms(Empty.getDefaultInstance());
            SwingUtilities.invokeLater(() -> {
                listModel.clear();
                roomList.getRoomsList().forEach(room ->
                        listModel.addElement(
                                String.format("%s (%d/2)",
                                        room.getRoomName(),
                                        room.getPlayersCount())
                        )
                );
            });
        }).start();
    }

    private void joinRoom() {
        int selectedIndex = roomsList.getSelectedIndex();
        if (selectedIndex != -1) {
            String selected = listModel.getElementAt(selectedIndex);
            currentGameId = selected.split(" ")[0];
            joinGame();
        }
    }

    private void joinGame() {
        cardLayout.show(mainPanel, "game");
        asyncStub.joinRoom(RoomRequest.newBuilder()
                .setRoomName(currentGameId)
                .setPlayerName(playerName)
                .build(), new GameStateObserver());
    }

    private void makeMove(int position) {
        new Thread(() -> {
            MoveResult result = blockingStub.makeMove(Move.newBuilder()
                    .setGameId(currentGameId)
                    .setPlayerName(playerName)
                    .setPosition(position)
                    .build());

            if (!result.getSuccess()) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(frame, result.getMessage()));
            }
        }).start();
    }

    private class GameStateObserver implements StreamObserver<GameState> {
        @Override
        public void onNext(GameState state) {
            SwingUtilities.invokeLater(() -> updateUI(state));
        }

        @Override
        public void onError(Throwable t) {
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(frame, "Error: " + t.getMessage()));
        }

        @Override
        public void onCompleted() {
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(frame, "Game finished"));
        }

        private void updateUI(GameState state) {
            List<String> board = state.getBoardList();
            for (int i = 0; i < 9; i++) {
                int row = i / 3;
                int col = i % 3;
                gridButtons[row][col].setText(board.get(i));
                gridButtons[row][col].setEnabled(board.get(i).isEmpty());
            }

            if (state.getCurrentPlayer().equals(playerName)) {
                ((JLabel)((BorderLayout)mainPanel.getComponent(2).getLayout().getLayoutComponent(BorderLayout.SOUTH)))
                        .setText("Your turn! (" + playerSymbol + ")");
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