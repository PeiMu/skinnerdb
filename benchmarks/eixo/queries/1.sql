SELECT eixo_1.number_of_records FROM eixo_1 WHERE ((CAST(EXTRACT(YEAR FROM eixo_1.data_de_inicio) AS LONG) IN (2013, 2014, 2015)) AND (NOT ((eixo_1.nome_da_sit_matricula_situacao_detalhada IN ('CANC_DESISTENTE', 'CANC_MAT_PRIM_OPCAO', 'CANC_SANO', 'CANC_SEM_FREQ_INICIAL', 'CANC_TURMA', 'DOC_INSUFIC', 'ESCOL_INSUFIC', 'INC _ITINERARIO', 'INSC_CANC', 'No Matriculado', 'NO_COMPARECEU', 'TURMA_CANC', 'VAGAS_INSUFIC')) OR (eixo_1.nome_da_sit_matricula_situacao_detalhada IS NULL))) AND (NOT (eixo_1.situacao_da_turma IN ('CANCELADA', 'CRIADA', 'PUBLICADA'))));
