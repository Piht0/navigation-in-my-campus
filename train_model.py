import tensorflow as tf
import numpy as np
import os

print("🚀 Начало обучения...")

mnist = tf.keras.datasets.mnist
(x_train, y_train), _ = mnist.load_data()

print(f"📊 Загружено {len(x_train)} изображений")

print("📐 Уменьшаем до 5x5...")
x_train = tf.image.resize(x_train[..., tf.newaxis], [5, 5])

x_train_np = x_train.numpy()
x_train = (x_train_np > 128).astype(np.float32)

x_train = 1.0 - x_train

print("🧠 Создаем модель...")

model = tf.keras.Sequential([
    tf.keras.layers.Flatten(input_shape=(5, 5, 1)),
    tf.keras.layers.Dense(32, activation='relu'),
    tf.keras.layers.Dense(10, activation='softmax')  # 10 цифр (0-9)
])

model.compile(
    optimizer='adam',
    loss='sparse_categorical_crossentropy',
    metrics=['accuracy']
)

print("📚 Обучаем модель (5)...")
model.fit(x_train, y_train, epochs=5, batch_size=128)

print("💾 Сохраняем в формат TFLite...")

converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]  # Оптимизация размера
tflite_model = converter.convert()

os.makedirs('assets/tflite', exist_ok=True)

with open('assets/tflite/digit_model.tflite', 'wb') as f:
    f.write(tflite_model)

print("✅ ГОТОВО!")

print("\n🧪 Тестируем модель...")
predictions = model.predict(x_train[:5])
predicted_digits = np.argmax(predictions, axis=1)
print(f"Предсказания: {predicted_digits}")
print(f"Реальные цифры: {y_train[:5]}")