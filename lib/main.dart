import 'package:flutter/material.dart';
import 'package:tflite_flutter/tflite_flutter.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Оценка ТГУ',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: const RatingScreen(),
    );
  }
}

class RatingScreen extends StatefulWidget {
  const RatingScreen({super.key});

  @override
  State<RatingScreen> createState() => _RatingScreenState();
}

class _RatingScreenState extends State<RatingScreen> {
  List<bool> pixels = List.filled(25, false);
  Interpreter? interpreter;
  String result = "Нарисуйте цифру и нажмите 'Распознать'";

  @override
  void initState() {
    super.initState();
    loadModel();
  }

  Future<void> loadModel() async {
    try {
      interpreter = await Interpreter.fromAsset('assets/tflite/digit_model.tflite');
      print("✅ Модель загружена!");
    } catch (e) {
      print("❌ Ошибка загрузки модели: $e");
    }
  }

  void recognizeDigit() {
    if (interpreter == null) {
      setState(() {
        result = "Модель ещё не загружена!";
      });
      return;
    }

    List<List<List<List<double>>>> input = List.generate(
      1,
      (_) => List.generate(
        5,
        (y) => List.generate(
          5,
          (x) => [pixels[y * 5 + x] ? 1.0 : 0.0],
        ),
      ),
    );

    List<List<double>> output = List.generate(1, (_) => List.filled(10, 0.0));

    interpreter!.run(input, output);

    int predictedDigit = 0;
    double maxProb = output[0][0];
    for (int i = 1; i < 10; i++) {
      if (output[0][i] > maxProb) {
        maxProb = output[0][i];
        predictedDigit = i;
      }
    }

    setState(() {
      result = "Распознано: $predictedDigit (уверенность: ${(maxProb * 100).toStringAsFixed(1)}%)";
    });

    print("Вероятности: ${output[0]}");
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Нарисуй оценку (0-9)')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text('Закрашивай клетки'),
            const SizedBox(height: 20),
            
            Container(
              width: 250,
              height: 250,
              decoration: BoxDecoration(border: Border.all(color: Colors.grey, width: 2)),
              child: GridView.builder(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 5,
                  mainAxisSpacing: 1,
                  crossAxisSpacing: 1,
                ),
                itemCount: 25,
                itemBuilder: (context, index) {
                  return GestureDetector(
                    onTap: () {
                      setState(() {
                        pixels[index] = !pixels[index];
                      });
                    },
                    child: Container(
                      color: pixels[index] ? Colors.black : Colors.white,
                    ),
                  );
                },
              ),
            ),

            const SizedBox(height: 20),

            ElevatedButton(
              onPressed: recognizeDigit,
              child: const Text('РАСПОЗНАТЬ'),
            ),

            const SizedBox(height: 20),

            // Результат
            Text(
              result,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              textAlign: TextAlign.center,
            ),

            const SizedBox(height: 10),

            // Кнопка очистки
            TextButton(
              onPressed: () {
                setState(() {
                  pixels = List.filled(25, false);
                  result = "Нарисуйте цифру и нажмите 'Распознать'";
                });
              },
              child: const Text('Очистить'),
            ),
          ],
        ),
      ),
    );
  }
}