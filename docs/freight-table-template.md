# Planilha modelo de tabela de frete

O modelo é baixado em `GET /api/v1/freight-tables/template` e importado em `POST /api/v1/freight-tables/import` (multipart: `carrierId` e `file`). A importação cria uma versão em `DRAFT`; se houver erros, nada é criado e a resposta `422` lista todos, cada um com a aba, a linha e a coluna (campo `Rotas!B12`, por exemplo).

A planilha tem quatro abas, com o cabeçalho na linha 1 e os dados a partir da linha 2. O modelo traz uma linha de exemplo em cada aba (a tabela de referência T: fator de cubagem 300, três faixas de peso, uma rota para 01000000–09999999 e uma área TDE). Linhas totalmente vazias são ignoradas.

## Convenções

- **CEP:** 8 dígitos, sem hífen. As colunas de CEP do modelo estão formatadas como texto para o Excel não perder o zero à esquerda; um CEP numérico (`1000000`) é completado com zeros (`01000000`).
- **Números:** aceitam ponto decimal (`1.20`) ou vírgula (`1,20`). Percentuais vão de 0 a 100 (`0.30` é 0,30 %).
- **Datas:** `AAAA-MM-DD` ou `DD/MM/AAAA`, ou uma célula de data do Excel.
- **Sim/Não:** `SIM` ou `NAO` (também `TRUE`/`FALSE`).
- **Valores sem ICMS** por padrão: marque `IncluiICMS = SIM` se os valores da tabela já incluem ICMS.

## Aba `Geral` (uma linha de dados)

| Coluna | Cabeçalho | Obrigatório | Descrição |
|---|---|---|---|
| A | `Nome` | sim | Nome da tabela |
| B | `InicioVigencia` | sim | Primeiro dia de vigência |
| C | `FimVigencia` | não | Último dia de vigência; vazio = sem fim |
| D | `IncluiICMS` | sim | `SIM` se os valores já incluem ICMS |
| E | `FatorCubagem` | sim | kg por m³ (maior que zero) |
| F | `FreteMinimo` | não | Valor mínimo do frete (padrão 0) |
| G | `TaxaDespacho` | não | Taxa de despacho somada ao frete (padrão 0) |

## Aba `Rotas` (uma linha por rota)

| Coluna | Cabeçalho | Obrigatório | Descrição |
|---|---|---|---|
| A | `Rota` | sim | Nome único da rota; liga a rota às faixas da aba `Faixas` |
| B | `CepDestinoInicio` | sim | CEP inicial de destino |
| C | `CepDestinoFim` | sim | CEP final de destino (≥ inicial) |
| D | `CepOrigemInicio` | não | CEP inicial de origem; se informado, informe também o final |
| E | `CepOrigemFim` | não | CEP final de origem |
| F | `ExcedentePorKg` | não | Valor por kg acima do fim da última faixa |
| G | `AdValoremPct` | não | Percentual (0–100) sobre o valor da carga |
| H | `AdValoremMinimo` | não | Valor mínimo do ad valorem |
| I | `GrisPct` | não | Percentual (0–100) sobre o valor da carga |
| J | `GrisMinimo` | não | Valor mínimo do GRIS |
| K | `PedagioPor100Kg` | não | Pedágio a cada 100 kg (fração arredondada para cima) |

Faixas de CEP de destino não podem se sobrepor para a mesma origem. Uma rota com origem pode sobrepor uma rota sem origem (a que casa com a origem tem prioridade).

## Aba `Faixas` (uma linha por faixa de peso)

| Coluna | Cabeçalho | Obrigatório | Descrição |
|---|---|---|---|
| A | `Rota` | sim | Nome de uma rota da aba `Rotas` |
| B | `DeKg` | sim | Início da faixa (kg) |
| C | `AteKg` | sim | Fim da faixa (kg), maior que o início |
| D | `Valor` | sim | Valor do frete peso na faixa |

Uma faixa cobre pesos **maiores** que `DeKg` e **até** `AteKg` (inclusive); a primeira inclui o zero. As faixas de cada rota, na ordem das linhas, devem começar em 0 kg e ser contínuas, sem buracos nem sobreposições (0–5, 5–10, 10–30).

## Aba `AreasEspeciais` (zero ou mais linhas)

| Coluna | Cabeçalho | Obrigatório | Descrição |
|---|---|---|---|
| A | `Tipo` | sim | `TDA` ou `TDE` |
| B | `CepInicio` | sim | CEP inicial da área |
| C | `CepFim` | sim | CEP final da área |
| D | `PercentualSobreFretePeso` | não | Percentual (0–100) sobre o frete peso |
| E | `Minimo` | não | Valor mínimo da taxa |

A taxa da área é o maior entre o percentual sobre o frete peso e o mínimo. Cada tipo de área é aplicado no máximo uma vez por cálculo.
