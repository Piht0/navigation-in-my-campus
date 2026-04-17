import numpy as np
import json
import os

# ── Эталонные паттерны цифр 0-9 на сетке 5x5 ─────────────────────────────────
# 1 = закрашено, 0 = пусто
# Паттерны подобраны так, чтобы человек мог их нарисовать на сетке 5x5

PATTERNS = {
    0: [[0,1,1,1,0],
        [1,0,0,0,1],
        [1,0,0,0,1],
        [1,0,0,0,1],
        [0,1,1,1,0]],

    1: [[0,0,1,0,0],
        [0,1,1,0,0],
        [0,0,1,0,0],
        [0,0,1,0,0],
        [0,1,1,1,0]],

    2: [[0,1,1,1,0],
        [0,0,0,1,0],
        [0,1,1,1,0],
        [0,1,0,0,0],
        [0,1,1,1,0]],

    3: [[0,1,1,1,0],
        [0,0,0,1,0],
        [0,0,1,1,0],
        [0,0,0,1,0],
        [0,1,1,1,0]],

    4: [[0,1,0,1,0],
        [0,1,0,1,0],
        [0,1,1,1,0],
        [0,0,0,1,0],
        [0,0,0,1,0]],

    5: [[0,1,1,1,0],
        [0,1,0,0,0],
        [0,1,1,1,0],
        [0,0,0,1,0],
        [0,1,1,1,0]],

    6: [[0,1,1,1,0],
        [0,1,0,0,0],
        [0,1,1,1,0],
        [0,1,0,1,0],
        [0,1,1,1,0]],

    7: [[0,1,1,1,0],
        [0,0,0,1,0],
        [0,0,1,0,0],
        [0,0,1,0,0],
        [0,0,1,0,0]],

    8: [[0,1,1,1,0],
        [0,1,0,1,0],
        [0,1,1,1,0],
        [0,1,0,1,0],
        [0,1,1,1,0]],

    9: [[0,1,1,1,0],
        [0,1,0,1,0],
        [0,1,1,1,0],
        [0,0,0,1,0],
        [0,1,1,1,0]],
}

GRID = 5

def augment(pattern, n_samples=3000):
    """Генерирует n_samples аугментированных версий паттерна."""
    base = np.array(pattern, dtype=np.float32)
    samples = []
    for _ in range(n_samples):
        img = base.copy()

        # Случайный сдвиг ±1 пиксель
        dy = np.random.randint(-1, 2)
        dx = np.random.randint(-1, 2)
        img = np.roll(img, dy, axis=0)
        img = np.roll(img, dx, axis=1)

        # Случайный шум: переключаем ~10% пикселей
        noise_mask = np.random.random((GRID, GRID)) < 0.10
        img = np.where(noise_mask, 1.0 - img, img)

        samples.append(img.flatten())
    return np.array(samples, dtype=np.float32)

# ── Генерация датасета ────────────────────────────────────────────────────────
print("Генерируем обучающий датасет из эталонных паттернов...")

X_list, y_list = [], []
for digit, pattern in PATTERNS.items():
    samples = augment(pattern, n_samples=5000)
    X_list.append(samples)
    y_list.extend([digit] * len(samples))

X_train = np.vstack(X_list)
y_train = np.array(y_list, dtype=np.int32)

# Перемешиваем
idx = np.random.permutation(len(X_train))
X_train, y_train = X_train[idx], y_train[idx]

print(f"Датасет: {len(X_train)} примеров ({len(PATTERNS)} цифр × 5000)")

# ── Обучение ──────────────────────────────────────────────────────────────────
try:
    import tensorflow as tf

    print("Создаем модель (TensorFlow)...")
    model = tf.keras.Sequential([
        tf.keras.Input(shape=(GRID * GRID,)),
        tf.keras.layers.Dense(128, activation='relu'),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(64,  activation='relu'),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(10,  activation='softmax')
    ])
    model.compile(optimizer='adam',
                  loss='sparse_categorical_crossentropy',
                  metrics=['accuracy'])

    print("Обучаем (30 эпох)...")
    model.fit(X_train, y_train, epochs=30, batch_size=256, validation_split=0.1)

    raw = model.get_weights()

except ImportError:
    # Если TensorFlow не установлен — обучаем вручную на numpy
    print("TensorFlow не найден, обучаем на чистом numpy...")

    def relu(x):       return np.maximum(0, x)
    def relu_d(x):     return (x > 0).astype(np.float32)
    def softmax(x):
        e = np.exp(x - x.max(axis=1, keepdims=True))
        return e / e.sum(axis=1, keepdims=True)

    np.random.seed(42)
    W1 = np.random.randn(25, 128).astype(np.float32) * 0.1
    b1 = np.zeros(128, dtype=np.float32)
    W2 = np.random.randn(128, 64).astype(np.float32) * 0.1
    b2 = np.zeros(64, dtype=np.float32)
    W3 = np.random.randn(64, 10).astype(np.float32) * 0.1
    b3 = np.zeros(10, dtype=np.float32)

    lr = 0.01
    for epoch in range(50):
        perm = np.random.permutation(len(X_train))
        total_loss = 0
        for i in range(0, len(X_train), 256):
            xb = X_train[perm[i:i+256]]
            yb = y_train[perm[i:i+256]]

            h1 = relu(xb @ W1 + b1)
            h2 = relu(h1 @ W2 + b2)
            out = softmax(h2 @ W3 + b3)

            m = len(xb)
            loss_grad = out.copy()
            loss_grad[np.arange(m), yb] -= 1
            loss_grad /= m

            dW3 = h2.T @ loss_grad
            db3 = loss_grad.sum(axis=0)
            dh2 = (loss_grad @ W3.T) * relu_d(h1 @ W2 + b2)
            dW2 = h1.T @ dh2
            db2 = dh2.sum(axis=0)
            dh1 = (dh2 @ W2.T) * relu_d(xb @ W1 + b1)
            dW1 = xb.T @ dh1
            db1 = dh1.sum(axis=0)

            W3 -= lr * dW3; b3 -= lr * db3
            W2 -= lr * dW2; b2 -= lr * db2
            W1 -= lr * dW1; b1 -= lr * db1

        if (epoch + 1) % 10 == 0:
            h1 = relu(X_train @ W1 + b1)
            h2 = relu(h1 @ W2 + b2)
            out = softmax(h2 @ W3 + b3)
            acc = (out.argmax(axis=1) == y_train).mean()
            print(f"Epoch {epoch+1}/50  accuracy: {acc:.4f}")

    raw = [W1, b1, W2, b2, W3, b3]

# ── Экспорт весов ─────────────────────────────────────────────────────────────
print("Сохраняем веса в JSON...")

layers = []
for i in range(0, len(raw), 2):
    layers.append({"w": np.array(raw[i]).tolist(),
                   "b": np.array(raw[i+1]).tolist()})

weights_dict = {"grid": GRID, "layers": layers}

os.makedirs('assets_android', exist_ok=True)
with open('assets_android/digit_weights.json', 'w') as f:
    json.dump(weights_dict, f)

print("ГОТОВО! Файл: assets_android/digit_weights.json")
print("Скопируй его в: app/src/main/assets/digit_weights.json")

# ── Как выглядят эталонные паттерны ──────────────────────────────────────────
print("\nЭталонные паттерны (что нужно рисовать):")
for digit, pattern in PATTERNS.items():
    print(f"\nЦифра {digit}:")
    for row in pattern:
        print("  " + " ".join("X" if p else "." for p in row))
