# VoteComDados — aplicação web

Next.js 16 (App Router) + TypeScript + Tailwind 4, com **export estático**.
Roda com dados de exemplo locais enquanto a API não existe.

## Pré-requisitos

- **Node.js 20.9 ou superior** (`node --version`). O Next 16 não roda em versões anteriores.
- npm (vem com o Node).

## Subir localmente

```bash
cd web
npm install     # só na primeira vez, ou quando as dependências mudarem
npm run dev
```

Abra **http://localhost:3000**. O servidor recarrega sozinho ao salvar um arquivo.

Para parar: `Ctrl+C` no terminal. Se a porta ficar presa depois de um
encerramento abrupto:

```bash
lsof -ti:3000 -sTCP:LISTEN | xargs kill
```

## O que vale a pena olhar

A aplicação usa dados de exemplo escolhidos para exercitar os **casos
difíceis**, não o caminho feliz. Vale abrir estes:

| Página | Endereço | O que demonstra |
|---|---|---|
| Busca | `/` | Filtro por cargo, UF e "somente com mandato". O estado fica na URL — recarregue e veja que se mantém |
| Perfil completo | `/politicos/a1000000-0000-4000-8000-000000000001` | Trajetória municipal → estadual → federal; abas de Projetos e Votações |
| Sem mandato anterior | `/politicos/a1000000-0000-4000-8000-000000000002` | O caso da maioria dos candidatos: diz "não exerceu mandato" em vez de mostrar aba vazia |
| Registro indeferido | `/politicos/a1000000-0000-4000-8000-000000000003` | Candidatura indeferida segue visível com o status; mandato de 1998 explica por que não há votos (a fonte só publica a partir de 2001) |
| Votação nominal | `/votacoes/555111` | Placar com a paleta validada para daltonismo |
| Votação simbólica | `/votacoes/555112` | Sem voto individual — e diz por quê, em vez de mostrar zero |
| Votação em comissão | `/votacoes/777001` | Marcada como comissão, que não tem o peso de plenário |
| Proposição | `/proposicoes/1197773` | Autoria completa: coautores fora da coorte aparecem sem link |
| Metodologia | `/sobre` | Cobertura por nível, glossário de votos, frescor por fonte |

A aba **Votações** do primeiro perfil reúne um voto de cada fonte, cada um
com sua nota metodológica: "Obstrução" e "Ausente" (apurado pela plataforma,
não pela Câmara) na Câmara; o voto da Alesp exibindo o rótulo original
*"Favorável ao parecer"* ao lado da categorização, e — a mesma pessoa,
noutra deliberação — "Voto em separado", que a Alesp também não classifica
como sim ou não; e a participação em **votação secreta do Senado**, marcada
como tal e não como recusa a votar.

Dica: alterne o tema do sistema entre claro e escuro — a paleta escura foi
validada separadamente, não derivada por inversão.

## Outros comandos

```bash
npm run build            # export estático em web/out
npm run typecheck        # tsc --noEmit
npm run lint

# Exigem o dev server rodando em outro terminal:
npm run acessibilidade   # auditoria WCAG 2.1 AA; falha o processo se houver violação
npm run telas            # capturas das telas principais em /tmp/vcd-shots
npm run telas-detalhe    # capturas das rotas de detalhe em /tmp/vcd-shots2
```

Para servir o build estático como em produção:

```bash
npm run build
npm start                # serve o conteúdo de web/out em localhost:3000
```

> `next start` **não** funciona aqui: ele exige um servidor Node, e este
> projeto gera export estático. O script `start` usa `serve` por isso.

## Ligar numa API real

Todo acesso a dados passa por `src/lib/api/cliente.ts`. Defina a variável e o
cliente troca as fixtures por HTTP, sem alterar nenhuma tela:

```bash
echo 'NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1' > .env.local
npm run dev
```

O contrato está em [`../docs/API.md`](../docs/API.md) e os tipos em
`src/lib/api/tipos.ts`.

## Estrutura

```
src/
  app/                     rotas (App Router)
  componentes/dominio/     componentes do domínio legislativo
  componentes/ui/          primitivos (abas acessíveis, estados de lista)
  lib/api/                 tipos, cliente e dados de exemplo
  lib/formato.ts           formatação pt-BR e fuso America/Sao_Paulo
```

Decisões de arquitetura, restrições conhecidas e requisitos de acessibilidade
estão em [`../docs/FRONTEND.md`](../docs/FRONTEND.md).
