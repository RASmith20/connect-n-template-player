package com.thg.accelerator23.connectn.ai.superchargedrobbot;

import com.thehutgroup.accelerator.connectn.player.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class SuperChargedRobBot extends Player {
  private static final int WIN_CONDITION = 4;
  private static final int MAX_DEPTH = 50;
  private static final long TIME_LIMIT_NANOS = 9_500_000_000L; // 9.5 seconds
  private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}}; // Right, Up, Diagonal-right, Diagonal-left

  private static final int WIN_SCORE = 1000000;
  private static final int OPEN_THREE_SCORE = 10000;
  private static final int DOUBLE_THREAT_SCORE = 50000;

  public SuperChargedRobBot(Counter counter) {
    super(counter, SuperChargedRobBot.class.getName());
  }

  @Override
  public int makeMove(Board board) {
    long startTime = System.nanoTime();
    int bestMove = board.getConfig().getWidth() / 2; // Default to center
    int bestScore = Integer.MIN_VALUE;

    // Check for immediate winning moves or blocking moves
    int immediateMove = findImmediateMove(board);
    if (immediateMove != -1) {
      return immediateMove;
    }

    // Iterative deepening with time limit
    final int[] depthHolder = {1};
    ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    try {
      for (; depthHolder[0] <= MAX_DEPTH; depthHolder[0]++) {
        int currentBestMove = bestMove;
        int currentBestScore = bestScore;

        List<Future<int[]>> futures = new ArrayList<>();
        for (int column : getColumnOrder(board.getConfig().getWidth(), bestMove)) {
          if (!isValidMove(board, column)) continue;

          futures.add(executor.submit(() -> {
            try {
              Board newBoard = new Board(board, column, getCounter());
              int score = minimaxWithTimeLimit(
                      newBoard,
                      depthHolder[0],
                      false,
                      Integer.MIN_VALUE,
                      Integer.MAX_VALUE,
                      startTime
              );
              return new int[]{column, score};
            } catch (TimeoutException | InvalidMoveException e) {
              return new int[]{-1, Integer.MIN_VALUE};
            }
          }));
        }

        for (Future<int[]> future : futures) {
          try {
            int[] result = future.get();
            int column = result[0];
            int score = result[1];

            if (column != -1 && score > currentBestScore) {
              currentBestScore = score;
              currentBestMove = column;
            }
          } catch (InterruptedException | ExecutionException e) {
            // Handle exceptions
          }
        }

        bestMove = currentBestMove;
        bestScore = currentBestScore;
      }
    } finally {
      executor.shutdownNow();
    }

    return bestMove;
  }

  private int findImmediateMove(Board board) {
    Counter myCounter = getCounter();
    Counter opponent = myCounter.getOther();

    // Check for winning moves, then blocking moves
    for (Counter counter : new Counter[]{myCounter, opponent}) {
      for (int column = 0; column < board.getConfig().getWidth(); column++) {
        if (!isValidMove(board, column)) continue;

        try {
          Board newBoard = new Board(board, column, counter);
          if (hasWon(newBoard, counter)) {
            return column;
          }
        } catch (InvalidMoveException e) {
          continue;
        }
      }
    }
    return -1;
  }

  private int minimaxWithTimeLimit(
          Board board,
          int depth,
          boolean isMaximizing,
          int alpha,
          int beta,
          long startTime
  ) throws TimeoutException {
    if (System.nanoTime() - startTime > TIME_LIMIT_NANOS) {
      throw new TimeoutException();
    }

    Counter myCounter = getCounter();
    Counter opponent = myCounter.getOther();

    // Check terminal conditions
    if (hasWon(board, myCounter)) return WIN_SCORE;
    if (hasWon(board, opponent)) return -WIN_SCORE;
    if (depth == 0 || isBoardFull(board)) {
      return evaluateBoard(board);
    }

    Counter currentCounter = isMaximizing ? myCounter : opponent;
    int bestValue = isMaximizing ? Integer.MIN_VALUE : Integer.MAX_VALUE;

    for (int column : getColumnOrder(board.getConfig().getWidth(), bestValue)) {
      if (!isValidMove(board, column)) continue;

      try {
        Board newBoard = new Board(board, column, currentCounter);
        int eval = minimaxWithTimeLimit(
                newBoard,
                depth - 1,
                !isMaximizing,
                alpha,
                beta,
                startTime
        );

        if (isMaximizing) {
          bestValue = Math.max(bestValue, eval);
          alpha = Math.max(alpha, eval);
        } else {
          bestValue = Math.min(bestValue, eval);
          beta = Math.min(beta, eval);
        }

        if (beta <= alpha) break;
      } catch (InvalidMoveException e) {
        // Skip invalid moves
      }
    }

    return bestValue;
  }

  private int evaluateBoard(Board board) {
    int score = 0;
    Counter myCounter = getCounter();
    Counter opponent = myCounter.getOther();

    score += evaluateThreats(board, myCounter);
    score -= evaluateThreats(board, opponent) * 10;

    score += evaluateCenterControl(board);

    return score;
  }

  private int evaluateThreats(Board board, Counter counter) {
    int threatScore = 0;

    for (int column = 0; column < board.getConfig().getWidth(); column++) {
      for (int row = 0; row < board.getConfig().getHeight(); row++) {
        Position pos = new Position(column, row);
        if (!board.hasCounterAtPosition(pos)) continue;
        if (board.getCounterAtPosition(pos) != counter) continue;

        for (int[] dir : DIRECTIONS) {
          int length = getRunLength(board, pos, counter, dir[0], dir[1]);
          if (length == 3) {
            Position before = new Position(pos.getX() - dir[0], pos.getY() - dir[1]);
            Position after = new Position(pos.getX() + (length * dir[0]), pos.getY() + (length * dir[1]));

            boolean startOpen = isValidPosition(board, before) && !board.hasCounterAtPosition(before);
            boolean endOpen = isValidPosition(board, after) && !board.hasCounterAtPosition(after);

            if (startOpen && endOpen) {
              threatScore += OPEN_THREE_SCORE;
            }
          }
        }
      }
    }

    return threatScore;
  }

  private int evaluateCenterControl(Board board) {
    int score = 0;
    int centerColumn = board.getConfig().getWidth() / 2;
    Counter myCounter = getCounter();

    for (int row = 0; row < board.getConfig().getHeight(); row++) {
      Position centerPos = new Position(centerColumn, row);
      if (board.hasCounterAtPosition(centerPos)) {
        score += board.getCounterAtPosition(centerPos) == myCounter ? 30 : -30;
      }
    }
    return score;
  }

  private boolean hasWon(Board board, Counter counter) {
    for (int column = 0; column < board.getConfig().getWidth(); column++) {
      for (int row = 0; row < board.getConfig().getHeight(); row++) {
        Position pos = new Position(column, row);
        if (!board.hasCounterAtPosition(pos) || board.getCounterAtPosition(pos) != counter) continue;

        for (int[] dir : DIRECTIONS) {
          if (checkLine(board, pos, counter, WIN_CONDITION, dir[0], dir[1])) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private boolean checkLine(Board board, Position start, Counter counter, int length, int dx, int dy) {
    for (int i = 0; i < length; i++) {
      Position pos = new Position(start.getX() + (dx * i), start.getY() + (dy * i));
      if (!isValidPosition(board, pos) || board.getCounterAtPosition(pos) != counter) {
        return false;
      }
    }
    return true;
  }

  private int getRunLength(Board board, Position start, Counter counter, int dx, int dy) {
    int length = 0;
    int x = start.getX();
    int y = start.getY();

    while (isValidPosition(board, new Position(x, y)) &&
            board.getCounterAtPosition(new Position(x, y)) == counter) {
      length++;
      x += dx;
      y += dy;
    }

    return length;
  }

  private int[] getColumnOrder(int width, int bestMoveFromPreviousIteration) {
    int[] columnOrder = new int[width];
    int index = 0;

    if (bestMoveFromPreviousIteration != -1) {
      columnOrder[index++] = bestMoveFromPreviousIteration;
    }

    for (int i = 0; i < width; i++) {
      if (i != bestMoveFromPreviousIteration) {
        columnOrder[index++] = i;
      }
    }

    return columnOrder;
  }

  private boolean isValidMove(Board board, int column) {
    try {
      return board.hasCounterAtPosition(new Position(column, board.getConfig().getHeight() - 1)) == false;
    } catch (IndexOutOfBoundsException e) {
      return false;
    }
  }

  private boolean isValidPosition(Board board, Position pos) {
    return pos.getX() >= 0 && pos.getX() < board.getConfig().getWidth() &&
            pos.getY() >= 0 && pos.getY() < board.getConfig().getHeight();
  }

  private boolean isBoardFull(Board board) {
    for (int column = 0; column < board.getConfig().getWidth(); column++) {
      if (isValidMove(board, column)) {
        return false;
      }
    }
    return true;
  }
}
