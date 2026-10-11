"""Mede o modelo geral e os reforcos estaticos nas amostras extraidas.

Somente diagnostico: nao treina nem altera modelos.
"""
from pathlib import Path

import numpy as np
import onnxruntime as ort


COMPUTER = Path(__file__).resolve().parents[2]
REPO = COMPUTER.parent
DATASET = COMPUTER / "data" / "dataset_static.npz"
ASSETS = REPO / "mobile" / "app" / "src" / "main" / "assets" / "letras_estaticas"


def probabilidades(caminho, x):
    sessao = ort.InferenceSession(str(caminho), providers=["CPUExecutionProvider"])
    return np.asarray(sessao.run(None, {"landmarks_input": x.astype(np.float32)})[1])


def contagens(valores):
    unicos, quantidades = np.unique(valores, return_counts=True)
    return " ".join(f"{nome}={qtd}" for nome, qtd in zip(unicos, quantidades))


def main():
    dados = np.load(DATASET, allow_pickle=True)
    x, y = dados["X"].astype(np.float32), dados["y"].astype(str)
    restritos = {
        linha.strip()
        for linha in (ASSETS / "reforcos_restritos.txt").read_text().splitlines()
        if linha.strip()
    }

    labels = [
        linha.strip()
        for linha in (ASSETS / "geral" / "labels.txt").read_text().splitlines()
        if linha.strip()
    ]
    geral_probs = probabilidades(ASSETS / "geral" / "model.onnx", x)
    ordem = np.argsort(geral_probs, axis=1)
    primeiro, segundo = ordem[:, -1], ordem[:, -2]
    conf = geral_probs[np.arange(len(x)), primeiro]
    margem = conf - geral_probs[np.arange(len(x)), segundo]
    geral = np.asarray([
        labels[i] if p >= 0.93 and m >= 0.30 else "-"
        for i, p, m in zip(primeiro, conf, margem)
    ])

    modelos = {}
    for label in sorted(restritos):
        probs = probabilidades(ASSETS / label / "model.onnx", x)
        modelos[label] = probs[:, 1] if probs.shape[1] >= 2 else probs[:, 0]
    nomes = sorted(modelos)
    matriz = np.column_stack([modelos[nome] for nome in nomes])
    melhor_idx = matriz.argmax(axis=1)
    melhor = matriz[np.arange(len(x)), melhor_idx]
    segundo = np.partition(matriz, -2, axis=1)[:, -2]
    reforco = np.asarray([
        nomes[i] if p >= 0.97 and p - s >= 0.30 else "-"
        for i, p, s in zip(melhor_idx, melhor, segundo)
    ])

    for classe in sorted(set(y)):
        mascara = y == classe
        print(f"\n{classe} ({mascara.sum()} amostras)")
        print("  geral:", contagens(geral[mascara]))
        print("  reforco:", contagens(reforco[mascara]))
        print("  concordam:", int(np.sum((geral == reforco)[mascara])))
        for nome in nomes:
            valores = modelos[nome][mascara]
            print(
                f"  {nome} min/med/max {valores.min():.3f} "
                f"{np.median(valores):.3f} {valores.max():.3f}; "
                f">=.97 {int((valores >= .97).sum())}"
            )


if __name__ == "__main__":
    main()
