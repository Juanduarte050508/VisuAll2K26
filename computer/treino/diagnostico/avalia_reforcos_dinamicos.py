"""Mostra como os modelos dinamicos ativos competem nas amostras extraidas.

Uso de diagnostico: nao treina nem grava nada. Serve para encontrar modelos
individuais que roubam outras letras e portoes que impedem a classe correta.
"""
from pathlib import Path

import numpy as np
import onnxruntime as ort


COMPUTER = Path(__file__).resolve().parents[2]
REPO = COMPUTER.parent
DATASET = COMPUTER / "data" / "dataset_dynamic.npz"
ASSETS = REPO / "mobile" / "app" / "src" / "main" / "assets" / "letras_dinamicas"

CONFIANCA_GERAL = 0.95
MARGEM_GERAL = 0.32
CONFIANCA_INDIVIDUAL = 0.97
MARGEM_INDIVIDUAL = 0.32
CONFIANCA_SEM_RIVAL = 0.99


def probabilidades(caminho, X):
    sessao = ort.InferenceSession(str(caminho), providers=["CPUExecutionProvider"])
    saidas = sessao.run(None, {"landmarks_input": X.astype(np.float32)})
    return np.asarray(saidas[1])


def resumo_contagens(valores):
    unicos, contagens = np.unique(valores, return_counts=True)
    return " ".join("%s=%d" % (u, n) for u, n in zip(unicos, contagens))


def main():
    dados = np.load(DATASET, allow_pickle=True)
    X, y = dados["X"].astype(np.float32), dados["y"].astype(str)

    labels = [linha.strip() for linha in (ASSETS / "geral" / "labels.txt").read_text().splitlines()
              if linha.strip()]
    pg = probabilidades(ASSETS / "geral" / "model.onnx", X)
    ordem = np.argsort(pg, axis=1)
    melhor_idx = ordem[:, -1]
    segundo_idx = ordem[:, -2]
    melhor = pg[np.arange(len(X)), melhor_idx]
    margem = melhor - pg[np.arange(len(X)), segundo_idx]
    geral = np.array([
        labels[i] if p >= CONFIANCA_GERAL and m >= MARGEM_GERAL else "-"
        for i, p, m in zip(melhor_idx, melhor, margem)
    ])

    modelos = {}
    for pasta in sorted(ASSETS.iterdir()):
        caminho = pasta / "model.onnx"
        if pasta.is_dir() and pasta.name != "geral" and caminho.exists():
            p = probabilidades(caminho, X)
            modelos[pasta.name] = p[:, 1] if p.shape[1] >= 2 else p[:, 0]

    nomes = sorted(modelos)
    matriz = np.column_stack([modelos[n] for n in nomes])
    idx_ind = matriz.argmax(axis=1)
    melhor_ind = matriz[np.arange(len(X)), idx_ind]
    segundo_ind = np.partition(matriz, -2, axis=1)[:, -2]
    margem_ind = melhor_ind - segundo_ind
    individual = np.array([
        nomes[i] if p >= CONFIANCA_INDIVIDUAL and m >= MARGEM_INDIVIDUAL else "-"
        for i, p, m in zip(idx_ind, melhor_ind, margem_ind)
    ])
    # Politica ativa: H/K podem resgatar o geral apenas com confianca alta; X
    # tem corte proprio porque sua distribuicao real termina em 0.842.
    limiares = {"H": 0.97, "K": 0.97, "X": 0.70}
    individual_calibrado = np.array([
        nomes[i] if p >= limiares.get(nomes[i], CONFIANCA_INDIVIDUAL)
        and m >= MARGEM_INDIVIDUAL else "-"
        for i, p, m in zip(idx_ind, melhor_ind, margem_ind)
    ])
    grupo = {"H", "K", "X"}
    seguro = []
    for base, reforco in zip(geral, individual_calibrado):
        if base in grupo:
            seguro.append(reforco)
        elif base == "-" and reforco in grupo:
            seguro.append(reforco)
        else:
            seguro.append(base)
    seguro = np.asarray(seguro)

    print("modelos individuais:", " ".join(nomes))
    for classe in sorted(set(y)):
        mascara = y == classe
        print("\n%s (%d amostras)" % (classe, mascara.sum()))
        print("  geral     :", resumo_contagens(geral[mascara]))
        print("  individuais:", resumo_contagens(individual[mascara]))
        print("  politica segura:", resumo_contagens(seguro[mascara]))
        for nome in nomes:
            v = modelos[nome][mascara]
            q75, q90 = np.quantile(v, [0.75, 0.90])
            print("  %-3s positivo min/med/q75/q90/max: %.3f %.3f %.3f %.3f %.3f"
                  "  >=.80: %d  >=.90: %d  >=.97: %d"
                  % (nome, v.min(), np.median(v), q75, q90, v.max(),
                     int((v >= 0.80).sum()), int((v >= 0.90).sum()),
                     int((v >= 0.97).sum())))


if __name__ == "__main__":
    main()
