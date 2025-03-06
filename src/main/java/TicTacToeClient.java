import com.example.tictactoe.*;
import com.example.tictactoe.TicTacToeGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class TicTacToeClient {
    private final ManagedChannel channel;
    private final TicTacToeGrpc.TicTacToeBlockingStub blockingStub;
    private final TicTacToeGrpc.TicTacToeStub asyncStub;
    private String currentGameId;
    private String playerName;

    public TicTacToeClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.blockingStub = TicTacToeGrpc.newBlockingStub(channel);
        this.asyncStub = TicTacToeGrpc.newStub(channel);
    }

    public void start() throws InterruptedException {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter your name: ");
        playerName = scanner.nextLine();

        while (true) {
            printMenu();
            int choice = Integer.parseInt(scanner.nextLine());

            switch (choice) {
                case 1: createRoom(scanner); break;
                case 2: listRooms(); break;
                case 3: joinRoom(scanner); break;
                case 4: shutdown(); return;
                default: System.out.println("Invalid option!");
            }
        }
    }

    private void printMenu() {
        System.out.println("\n1. Create room");
        System.out.println("2. List rooms");
        System.out.println("3. Join room");
        System.out.println("4. Exit");
        System.out.print("Choose option: ");
    }

    private void createRoom(Scanner scanner) {
        System.out.print("Enter room name: ");
        String roomName = scanner.nextLine();

        RoomResponse response = blockingStub.createRoom(RoomRequest.newBuilder()
                .setRoomName(roomName)
                .setPlayerName(playerName)
                .build());

        if (response.getSuccess()) {
            currentGameId = response.getRoomId();
            System.out.println("Room created! ID: " + currentGameId);
            waitForGameStart();
        } else {
            System.out.println("Error: " + response.getMessage());
        }
    }

    private void listRooms() {
        RoomList roomList = blockingStub.listRooms(Empty.getDefaultInstance());
        System.out.println("\nAvailable rooms:");
        roomList.getRoomsList().forEach(room ->
                System.out.printf("[%s] %s - Players: %d/2%n",
                        room.getRoomId(),
                        room.getRoomName(),
                        room.getPlayersCount()));
    }

    private void joinRoom(Scanner scanner) {
        System.out.print("Enter room ID: ");
        currentGameId = scanner.nextLine();

        asyncStub.joinRoom(RoomRequest.newBuilder()
                .setRoomName(currentGameId)
                .setPlayerName(playerName)
                .build(), new GameStateObserver());

        System.out.println("Joining room...");
    }

    private void waitForGameStart() {
        asyncStub.joinRoom(RoomRequest.newBuilder()
                .setRoomName(currentGameId)
                .setPlayerName(playerName)
                .build(), new GameStateObserver());
    }

    private void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    private class GameStateObserver implements StreamObserver<GameState> {
        @Override
        public void onNext(GameState state) {
            System.out.println("\n=== Game Update ===");
            System.out.println("Status: " + state.getStatus());
            printBoard(state.getBoardList());

            if (state.getStatus().startsWith("IN_PROGRESS")) {
                startGameLoop();
            }
        }

        @Override
        public void onError(Throwable t) {
            System.err.println("Connection error: " + t.getMessage());
        }

        @Override
        public void onCompleted() {
            System.out.println("Game finished");
        }

        private void printBoard(List<String> board) {
            System.out.println("\n  " + getCell(board, 0) + " | " + getCell(board, 1) + " | " + getCell(board, 2));
            System.out.println("-----------");
            System.out.println("  " + getCell(board, 3) + " | " + getCell(board, 4) + " | " + getCell(board, 5));
            System.out.println("-----------");
            System.out.println("  " + getCell(board, 6) + " | " + getCell(board, 7) + " | " + getCell(board, 8));
        }

        private String getCell(List<String> board, int pos) {
            String cell = board.get(pos);
            return cell.isEmpty() ? " " : cell;
        }
    }

    private void startGameLoop() {
        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("\nEnter position (1-9) or 'q' to quit: ");
                String input = scanner.nextLine();

                if (input.equalsIgnoreCase("q")) {
                    System.exit(0);
                }

                try {
                    int position = Integer.parseInt(input) - 1;
                    MoveResult result = blockingStub.makeMove(Move.newBuilder()
                            .setGameId(currentGameId)
                            .setPlayerName(playerName)
                            .setPosition(position)
                            .build());

                    if (!result.getSuccess()) {
                        System.out.println("Error: " + result.getMessage());
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Invalid input!");
                }
            }
        }).start();
    }

    public static void main(String[] args) throws InterruptedException {
        TicTacToeClient client = new TicTacToeClient("localhost", 50051);
        client.start();
    }
}