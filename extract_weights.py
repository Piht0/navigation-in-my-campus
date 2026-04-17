"""
Извлекает веса из готового digit_model.tflite и сохраняет их в JSON
для использования в Android (чистый Kotlin, без TFLite).

Запуск:
  python extract_weights.py

Нужен только tensorflow (pip install tensorflow).
Файл digit_model.tflite должен лежать рядом со скриптом,
или укажи путь вручную в переменной MODEL_PATH.
"""

import json
import os
import numpy as np
import tensorflow as tf

MODEL_PATH = "digit_model.tflite"   # положи tflite рядом со скриптом

if not os.path.exists(MODEL_PATH):
    print(f"Файл не найден: {MODEL_PATH}")
    print("Скачай digit_model.tflite из репозитория одногруппника и положи рядом с этим скриптом.")
    exit(1)

interpreter = tf.lite.Interpreter(model_path=MODEL_PATH)
interpreter.allocate_tensors()

tensors = interpreter.get_tensor_details()

print("Тензоры в модели:")
for t in tensors:
    print(f"  [{t['index']}] {t['name']:50s}  shape={t['shape']}")

# Извлекаем веса через interpreter.tensor(index)() — работает для константных тензоров
# Из вывода выше известны индексы:
#   [4] MatMul Dense1 → W1, shape (32, 25) → нужно транспонировать в (25, 32)
#   [1] BiasAdd Dense1 → b1, shape (32,)
#   [3] MatMul Dense2 → W2, shape (10, 32) → нужно транспонировать в (32, 10)
#   [2] BiasAdd Dense2 → b2, shape (10,)

def get_weight(idx):
    return interpreter.tensor(idx)().copy()

# Находим нужные тензоры по имени
idx_w1 = idx_b1 = idx_w2 = idx_b2 = None
for t in tensors:
    name  = t['name']
    shape = tuple(t['shape'])
    if shape == (32, 25) and 'MatMul' in name:
        idx_w1 = t['index']
    elif shape == (32,) and ('Bias' in name or 'Relu' in name):
        idx_b1 = t['index']
    elif shape == (10, 32) and 'MatMul' in name:
        idx_w2 = t['index']
    elif shape == (10,) and 'Bias' in name:
        idx_b2 = t['index']

if None in (idx_w1, idx_b1, idx_w2, idx_b2):
    print(f"\nНе нашли все тензоры: w1={idx_w1} b1={idx_b1} w2={idx_w2} b2={idx_b2}")
    print("Используй train_model_export.py для переобучения.")
    exit(1)

w1 = get_weight(idx_w1).T   # (32,25) → (25,32)
b1 = get_weight(idx_b1)     # (32,)
w2 = get_weight(idx_w2).T   # (10,32) → (32,10)
b2 = get_weight(idx_b2)     # (10,)

result = {
    "w1": w1.tolist(),
    "b1": b1.tolist(),
    "w2": w2.tolist(),
    "b2": b2.tolist()
}

os.makedirs("assets_android", exist_ok=True)
out_path = "assets_android/digit_weights.json"
with open(out_path, "w") as f:
    json.dump(result, f)

print(f"\nГотово! Файл сохранён: {out_path}")
print("Скопируй его в: app/src/main/assets/digit_weights.json")
