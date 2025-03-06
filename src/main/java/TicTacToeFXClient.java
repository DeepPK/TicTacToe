import com.example.tictactoe.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class TicTacToeFXClient extends Application {
    private ManagedChannel channel;
    private TicTacToeGrpc.TicTacToeBlockingStub blockingStub;
    private TicTacToeGrpc.TicTacToeStub asyncStub;

    private String playerName;
    private String currentGameId;
    private String playerSymbol;

    private Stage primaryStage;
    private TextField nameField;
    private ListView<String> roomsList;
    private GridPane gameGrid;
    private Label statusLabel;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        setupConnection();
        showLoginScreen();
    }

    private void setupConnection() {
        channel = ManagedChannelBuilder.forAddress("localhost", 50051)
                .usePlaintext()
                .build();
        blockingStub = TicTacToeGrpc.newBlockingStub(channel);
        asyncStub = TicTacToeGrpc.newStub(channel);
    }

    private void showLoginScreen() {
        VBox root = new VBox(20);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);

        nameField = new TextField();
        nameField.setPromptText("Enter your name");
        nameField.setMaxWidth(200);

        Button loginButton = new Button("Login");
        loginButton.setOnAction(e -> {
            playerName = nameField.getText();
            if (!playerName.isEmpty()) {
                showMainMenu();
            }
        });

        root.getChildren().addAll(nameField, loginButton);
        primaryStage.setScene(new Scene(root, 400, 300));
        primaryStage.setTitle("Tic Tac Toe - Login");
        primaryStage.show();
    }

    private void showMainMenu() {
        VBox root = new VBox(20);
        root.setPadding(new Insets(20));

        Button createRoomButton = new Button("Create New Room");
        createRoomButton.setOnAction(e -> createRoom());

        Button refreshButton = new Button("Refresh Rooms");
        refreshButton.setOnAction(e -> refreshRooms());

        roomsList = new ListView<>();
        roomsList.setPrefHeight(200);

        root.getChildren().addAll(
                new Label("Available Rooms:"),
                roomsList,
                createRoomButton,
                refreshButton
        );

        refreshRooms();
        primaryStage.setScene(new Scene(root, 400, 400));
        primaryStage.setTitle("Tic Tac Toe - Main Menu");
    }

    private void createRoom() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Create Room");
        dialog.setHeaderText("Enter Room Name");
        dialog.showAndWait().ifPresent(roomName -> {
            RoomResponse response = blockingStub.createRoom(
                    RoomRequest.newBuilder()
                            .setRoomName(roomName)
                            .setPlayerName(playerName)
                            .build());

            if (response.getSuccess()) {
                currentGameId = response.getRoomId();
                joinGame();
            }
        });
    }

    private void refreshRooms() {
        RoomList roomList = blockingStub.listRooms(Empty.getDefaultInstance());
        Platform.runLater(() -> {
            roomsList.getItems().clear();
            roomList.getRoomsList().forEach(room ->
                    roomsList.getItems().add(
                            String.format("%s (%d/2 players)",
                                    room.getRoomName(),
                                    room.getPlayersCount())
                    )
            );
        });
    }

    private void joinGame() {
        VBox root = new VBox(20);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);

        statusLabel = new Label("Waiting for players...");
        statusLabel.setFont(Font.font(16));

        gameGrid = createGameGrid();

        root.getChildren().addAll(statusLabel, gameGrid);
        primaryStage.setScene(new Scene(root, 600, 600));
        primaryStage.setTitle("Tic Tac Toe - Game");

        asyncStub.joinRoom(RoomRequest.newBuilder()
                .setRoomName(currentGameId)
                .setPlayerName(playerName)
                .build(), new GameStateObserver());
    }

    private GridPane createGameGrid() {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(10);
        grid.setVgap(10);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int position = row * 3 + col;
                Button cell = new Button();
                cell.setPrefSize(100, 100);
                cell.setFont(Font.font(24));
                cell.setOnAction(e -> makeMove(position));
                grid.add(cell, col, row);
            }
        }
        return grid;
    }

    private void makeMove(int position) {
        MoveResult result = blockingStub.makeMove(Move.newBuilder()
                .setGameId(currentGameId)
                .setPlayerName(playerName)
                .setPosition(position)
                .build());

        if (!result.getSuccess()) {
            showAlert("Invalid Move", result.getMessage());
        }
    }

    private class GameStateObserver implements StreamObserver<GameState> {
        @Override
        public void onNext(GameState state) {
            Platform.runLater(() -> {
                statusLabel.setText("Status: " + state.getStatus());
                updateBoard(state.getBoardList());

                if (state.getCurrentPlayer().equals(playerName)) {
                    statusLabel.setText("Your turn (" + playerSymbol + ")");
                }
            });
        }

        @Override
        public void onError(Throwable t) {
            Platform.runLater(() ->
                    showAlert("Connection Error", t.getMessage()));
        }

        @Override
        public void onCompleted() {
            Platform.runLater(() ->
                    showAlert("Game Over", "The game has ended"));
        }

        private void updateBoard(List<String> board) {
            for (int i = 0; i < 9; i++) {
                Button cell = (Button) gameGrid.getChildren().get(i);
                String symbol = board.get(i);
                cell.setText(symbol.isEmpty() ? "" : symbol);
                cell.setDisable(!symbol.isEmpty());
            }
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    @Override
    public void stop() throws Exception {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
        super.stop();
    }
}
