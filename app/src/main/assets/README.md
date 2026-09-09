# Model asset

Place a compatible TensorFlow Lite object-detection model here as:

```text
yolo_model.tflite
```

The model binary is intentionally excluded from this repository. Before distributing a model, document its architecture, expected input/output tensors, class order, training dataset, evaluation split, metrics, license and attribution.

The current app expects the class order used in `MainActivity.kt`:

1. horizontal guidance paving
2. vertical guidance paving
3. warning paving
