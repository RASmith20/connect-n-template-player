package com.thg.accelerator23.connectn.ai.superchargedrobbot;

import com.thehutgroup.accelerator.connectn.player.*;

import java.util.concurrent.TimeoutException;

public class SuperChargedRobBot extends Player {
  private static final int WIN_CONDITION = 4;
  private static final int MAX_DEPTH = 50;
  private static final long TIME_LIMIT_NANOS = 9_500_000_000L; // 9.5 seconds
  private static final int[][] DIRECTIONS = {{1,0}, {0,1}, {1,1}, {1,-1}}; // Right, Up, Diagonal-right, Diagonal-left

  private static final int WIN_SCORE = 1_000_000;
  private static final int OPEN_THREE_SCORE = 10_000;
  private static final int DOUBLE_THREAT_SCORE = 50_000;

  public SuperChargedRobBot(Counter counter) {
    super(counter, SuperChargedRobBot.class.getName());
  }

  @Override
  public int makeMove(Board board) {
    long startTime = System.nanoTime();
    int boardWidth = board.getConfig().getWidth();
    int bestMove = boardWidth / 2; // Default to center column

    // Check for immediate winning moves or blocking moves
    int immediateMove = findImmediateMove(board);
    if (immediateMove != -1) {
      return immediateMove;
    }

    // Iterative deepening with time limit
    for (int depth = 1; depth <= MAX_DEPTH; depth++) {
      int currentBestMove = findBestMoveAtDepth(board, depth, startTime);

      if (System.nanoTime() - startTime > TIME_LIMIT_NANOS) break;

      if (currentBestMove != -1) {
        bestMove = currentBestMove;
      }
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
        }
      }
    }
    return -1;
  }

  private int findBestMoveAtDepth(Board board, int depth, long startTime) {
    int bestMove = -1;
    int bestScore = Integer.MIN_VALUE;
    int[] columns = getColumnOrder(board.getConfig().getWidth());

    for (int column : columns) {
      if (!isValidMove(board, column)) continue;

      try {
        Board newBoard = new Board(board, column, getCounter());
        int score = minimax(newBoard, depth, false, Integer.MIN_VALUE, Integer.MAX_VALUE, startTime);

        if (score > bestScore) {
          bestScore = score;
          bestMove = column;
        }
      } catch (TimeoutException | InvalidMoveException e) {
        break;
      }
    }

    return bestMove;
  }

  private int minimax(Board board, int depth, boolean isMaximizing,
                      int alpha, int beta, long startTime) throws TimeoutException {
    if (System.nanoTime() - startTime > TIME_LIMIT_NANOS)
      throw new TimeoutException();

    Counter myCounter = getCounter();
    Counter opponent = myCounter.getOther();

    // Terminal conditions
    if (hasWon(board, myCounter)) return WIN_SCORE;
    if (hasWon(board, opponent)) return -WIN_SCORE;
    if (depth == 0 || isBoardFull(board)) return evaluateBoard(board);

    Counter currentPlayer = isMaximizing ? myCounter : opponent;
    int bestValue = isMaximizing ? Integer.MIN_VALUE : Integer.MAX_VALUE;
    int[] columns = getColumnOrder(board.getConfig().getWidth());

    for (int column : columns) {
      if (!isValidMove(board, column)) continue;

      try {
        Board newBoard = new Board(board, column, currentPlayer);
        int eval = minimax(newBoard, depth - 1, !isMaximizing, alpha, beta, startTime);

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
    score -= evaluateThreats(board, opponent) * 2;
    score += evaluateCenterControl(board);

    return score;
  }

  private int evaluateThreats(Board board, Counter counter) {
    int threatScore = 0;
    int doubleThreats = 0;

    // Check for open threes and double threats
    for (int column = 0; column < board.getConfig().getWidth(); column++) {
      for (int row = 0; row < board.getConfig().getHeight(); row++) {
        Position pos = new Position(column, row);
        if (!board.hasCounterAtPosition(pos)) continue;
        if (board.getCounterAtPosition(pos) != counter) continue;

        int openThreesAtPosition = 0;

        for (int[] dir : DIRECTIONS) {
          int length = getRunLength(board, pos, counter, dir[0], dir[1]);
          if (length == 3) {
            Position before = new Position(pos.getX() - dir[0], pos.getY() - dir[1]);
            Position after = new Position(pos.getX() + (length * dir[0]), pos.getY() + (length * dir[1]));

            boolean startOpen = isValidPosition(board, before) && !board.hasCounterAtPosition(before);
            boolean endOpen = isValidPosition(board, after) && !board.hasCounterAtPosition(after);

            if (startOpen && endOpen) {
              openThreesAtPosition++;
              threatScore += OPEN_THREE_SCORE;
            } else if (startOpen || endOpen) {
              threatScore += OPEN_THREE_SCORE / 2;
            }
          }
        }

        if (openThreesAtPosition >= 2) {
          doubleThreats++;
        }
      }
    }

    threatScore += doubleThreats * DOUBLE_THREAT_SCORE;
    return threatScore;
  }

  private int evaluateCenterControl(Board board) {
    int occupiedSpaces = countOccupiedSpaces(board);
    int totalSpaces = board.getConfig().getWidth() * board.getConfig().getHeight();
    double occupancyRatio = (double)occupiedSpaces / (double)totalSpaces;
    int centerWeight = (int)(30.0 * Math.pow(occupancyRatio, 0.5) * 2.0);

    int centerColumn = board.getConfig().getWidth() / 2;
    int score = 0;
    Counter myCounter = getCounter();


    for (int row = 0; row < board.getConfig().getHeight(); row++) {
      Position centerPos = new Position(centerColumn, row);
      if (board.hasCounterAtPosition(centerPos)) {
        score += board.getCounterAtPosition(centerPos) == myCounter ? centerWeight : -centerWeight;
      }
    }
    return score;
  }

  private int countOccupiedSpaces(Board board) {
    int occupied = 0;
    for (int column = 0; column < board.getConfig().getWidth(); column++) {
      for (int row = 0; row < board.getConfig().getHeight(); row++) {
        if (board.hasCounterAtPosition(new Position(column, row))) {
          occupied++;
        }
      }
    }
    return occupied;
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

  private int[] getColumnOrder(int width) {
    int[] columnOrder = new int[width];
    int center = width / 2;
    int left = center - 1;
    int right = center + 1;
    int index = 0;

    columnOrder[index++] = center;
    while (left >= 0 || right < width) {
      if (right < width) columnOrder[index++] = right++;
      if (left >= 0) columnOrder[index++] = left--;
    }

    return columnOrder;
  }

  private boolean isValidMove(Board board, int column) {
    return isValidPosition(board, new Position(column, board.getConfig().getHeight() - 1)) &&
            !board.hasCounterAtPosition(new Position(column, board.getConfig().getHeight() - 1));
  }

  private boolean isBoardFull(Board board) {
    for (int column = 0; column < board.getConfig().getWidth(); column++) {
      if (isValidMove(board, column)) return false;
    }
    return true;
  }

  private boolean isValidPosition(Board board, Position pos) {
    int x = pos.getX();
    int y = pos.getY();
    int boardWidth = board.getConfig().getWidth();
    int boardHeight = board.getConfig().getHeight();

    return x >= 0 &&
            x < boardWidth &&
            y >= 0 &&
            y < boardHeight;
  }
}