# Library index (generated from FHIRconnect-mapping-lib on 2026-10-02)

Regenerate with `python scripts/validate_mapping.py <mapping-lib> --index --format md`. Use it for the reuse check: a model mapping listed here is reused via `slotArchetype` / `context.archetypes`, never rewritten. Names are `metadata.name` values; files are relative to the library root.

| type | name | archetype / template | resource / profile | extends / start | file |
|---|---|---|---|---|---|
| context | `IPS.context` | International Patient Summary | http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips | start=COMPOSITION.health_summary.v1 | projects/org.openehr/IPS/IPS.context.yml |
| context | `KDS_Person.context` | KDS_Person | https://www.medizininformatik-initiative.de/fhir/core/modul-person/StructureDefinition/Patient | start=ADMIN_ENTRY.person_data.v0 | projects/org.highmed/KDS/person/person.context.yaml |
| context | `KDS_Prozedur.context` | KDS_Prozedur | https://www.medizininformatik-initiative.de/fhir/core/modul-prozedur/StructureDefinition/Procedure | start=ACTION.procedure.v1 | projects/org.highmed/KDS/procedure/procedure.context.yaml |
| context | `KDS_Studienteilnahme.context` | Studienteilnahme | https://www.medizininformatik-initiative.de/fhir/modul-consent/StructureDefinition/mii-pr-consent-einwilligung | start=ACTION.informed_consent.v0 | projects/org.highmed/KDS/studienteilnahme/studienteilnahme.context.yaml |
| context | `KDS_Vitalstatus.context` | KDS_Vitalstatus | https://www.medizininformatik-initiative.de/fhir/core/modul-person/StructureDefinition/Vitalstatus | start=EVALUATION.vital_status.v1 | projects/org.highmed/KDS/vitalstatus/KDS_Vitalstatus.context.yaml |
| context | `KDS_death.context` | KDS_Todesursache | https://www.medizininformatik-initiative.de/fhir/core/modul-person/StructureDefinition/Todesursache | start=EVALUATION.cause_of_death.v1 | projects/org.highmed/KDS/todesursache/KDS_Todesursache.context.yaml |
| context | `KDS_diagnose.context` | KDS_Diagnose | https://www.medizininformatik-initiative.de/fhir/core/modul-diagnose/StructureDefinition/Diagnose | start=EVALUATION.problem_diagnosis.v1 | projects/org.highmed/KDS/diagnose/KDS_diagnose.context.yaml |
| context | `KDS_fall_einfach.context` | KDS_Fall_einfach | https://www.medizininformatik-initiative.de/fhir/core/modul-fall/StructureDefinition/KontaktGesundheitseinrichtung | start=ADMIN_ENTRY.episode_institution_local.v0 | projects/org.highmed/KDS/fall/KDS_fall_einfach.context.yaml |
| context | `KDS_laborauftrag.context` | KDS_Laborauftrag | https://www.medizininformatik-initiative.de/fhir/core/modul-labor/StructureDefinition/ServiceRequestLab | start=INSTRUCTION.service_request.v1 | projects/org.highmed/KDS/laborauftrag/KDS_laborauftrag.context.yaml |
| context | `KDS_laborbericht.context` | KDS_Laborbericht | https://www.medizininformatik-initiative.de/fhir/core/modul-labor/StructureDefinition/DiagnosticReportLab | start=OBSERVATION.laboratory_test_result.v1 | projects/org.highmed/KDS/laborbericht/KDS_laborbericht.context.yaml |
| context | `KDS_medikationseintrag.context` | KDS_Medikationseintrag | https://www.medizininformatik-initiative.de/fhir/core/modul-medikation/StructureDefinition/MedicationStatement | start=OBSERVATION.medication_statement.v0 | projects/org.highmed/KDS/medikationseintrag/KDS_medikationseintrag.context.yaml |
| context | `KDS_medikationsverabreichung.context` | KDS_Medikamentenverabreichungen | https://www.medizininformatik-initiative.de/fhir/core/modul-medikation/StructureDefinition/MedicationAdministration | start=ACTION.medication.v1 | projects/org.highmed/KDS/medikationsverabreichung/KDS_medikationsverabreichung.context.yaml |
| context | `eehrxf_lab.context` | EHDS - Laboratory report | http://hl7.eu/fhir/laboratory/StructureDefinition/DiagnosticReport-eu-lab | start=OBSERVATION.laboratory_test_result.v1 | projects/org.openehr/EEHRxF/lab/bundle/lab.context.yml |
| extension | `IPS_adverse_reaction` |  |  | EVALUATION.adverse_reaction_risk.v2 | projects/org.openehr/IPS/IPS_adverse_reaction.yml |
| extension | `IPS_composition` |  |  | COMPOSITION.health_summary.v1 | projects/org.openehr/IPS/IPS_composition.yml |
| extension | `IPS_problem_diagnose` |  |  | EVALUATION.problem_diagnosis.v1 | projects/org.openehr/IPS/IPS_problem_diagnosis.yml |
| extension | `IPS_problem_qualifier` |  |  | CLUSTER.problem_qualifier.v2 | projects/org.openehr/IPS/IPS_problem_qualifier.yml |
| extension | `KDS_address` |  |  | CLUSTER.address.v1 | projects/org.highmed/KDS/person/KDS_address.yml |
| extension | `KDS_address_pseudo` |  |  | CLUSTER.address.v1 | projects/org.highmed/KDS/person_pseudo/KDS_address.yml |
| extension | `KDS_admin_entry_perso_pseudo` |  |  | ADMIN_ENTRY.person_data.v0 | projects/org.highmed/KDS/person_pseudo/KDS_pseudo_admin_person.yml |
| extension | `KDS_admin_entry_person.v0` |  |  | ADMIN_ENTRY.person_data.v0 | projects/org.highmed/KDS/person/KDS_admin_entry_person.yml |
| extension | `KDS_anatomical_location` |  |  | CLUSTER.anatomical_location.v1 | projects/org.highmed/KDS/diagnose/KDS_anatomical_location.yml |
| extension | `KDS_anatomical_location_prozedur` |  |  | CLUSTER.anatomical_location.v1 | projects/org.highmed/KDS/procedure/KDS_anatomical_location.yml |
| extension | `KDS_cause_of_death` |  |  | EVALUATION.cause_of_death.v1 | projects/org.highmed/KDS/todesursache/KDS_cause_of_death.yml |
| extension | `KDS_cluster_person.v1` |  |  | CLUSTER.person.v1 | projects/org.highmed/KDS/person/KDS_cluster_person.yml |
| extension | `KDS_composition` |  |  | COMPOSITION.fall.v1.encounter | projects/org.highmed/KDS/fall/KDS_composition.yml |
| extension | `KDS_composition.Condition` |  |  | COMPOSITION.report_result.v1.Condition | projects/org.highmed/KDS/diagnose/KDS_composition.yml |
| extension | `KDS_composition.DiagnosticReport` |  |  | COMPOSITION.report_result.v1.DiagnosticReport | projects/org.highmed/KDS/laborbericht/KDS_composition.yml |
| extension | `KDS_composition.MedicationAdministration` |  |  | COMPOSITION.report.v1.MedicationAdministration | projects/org.highmed/KDS/medikationsverabreichung/KDS_composition.yml |
| extension | `KDS_composition.MedicationStatement` |  |  | COMPOSITION.medication_list.v1.MedicationStatement | projects/org.highmed/KDS/medikationseintrag/KDS_composition.yml |
| extension | `KDS_composition_person.v0` |  |  | COMPOSITION.person.v0 | projects/org.highmed/KDS/person/KDS_Composition.yml |
| extension | `KDS_episode_institution_local` |  |  | ADMIN_ENTRY.episode_institution_local.v0 | projects/org.highmed/KDS/fall/KDS_episode_institution_local.yml |
| extension | `KDS_fall_problem_diagnose` |  |  | EVALUATION.problem_diagnosis.v1 | projects/org.highmed/KDS/fall/KDS_fall_problem_diagnose.yml |
| extension | `KDS_gender.v1` |  |  | EVALUATION.gender.v1 | projects/org.highmed/KDS/person/KDS_gender.yml |
| extension | `KDS_gender_pseudo.v1` |  |  | EVALUATION.gender.v1 | projects/org.highmed/KDS/person_pseudo/KDS_gender.yml |
| extension | `KDS_informed_consent` |  |  | ACTION.informed_consent.v0 | projects/org.highmed/KDS/studienteilnahme/KDS_informed_consent.yaml |
| extension | `KDS_laborauftrag` |  |  | INSTRUCTION.service_request.v1 | projects/org.highmed/KDS/laborauftrag/KDS_laborauftrag.yml |
| extension | `KDS_laborbericht` |  |  | OBSERVATION.laboratory_test_result.v1 | projects/org.highmed/KDS/laborbericht/KDS_laborbericht.yml |
| extension | `KDS_lebensphase` |  |  | CLUSTER.lebensphase.v0 | projects/org.highmed/KDS/diagnose/KDS_lebensphase.yml |
| extension | `KDS_medication.v2` |  |  | CLUSTER.medication.v2 | projects/org.highmed/KDS/medikationsverabreichung/KDS_medication.yml |
| extension | `KDS_medication.v2.substance` |  |  | CLUSTER.medication.v2.substance | projects/org.highmed/KDS/medikationsverabreichung/KDS_medication_substance.yml |
| extension | `KDS_medication.v3` |  |  | CLUSTER.medication.v2 | projects/org.highmed/KDS/medikationseintrag/KDS_medication.yml |
| extension | `KDS_medikamentenstatement` |  |  | OBSERVATION.medication_statement.v0 | projects/org.highmed/KDS/medikationseintrag/KDS_medikationseintrag.yml |
| extension | `KDS_medikamentenverabreichung` |  |  | ACTION.medication.v1 | projects/org.highmed/KDS/medikationsverabreichung/KDS_medikamentenverabreichung.yml |
| extension | `KDS_problem_diagnose` |  |  | EVALUATION.problem_diagnosis.v1 | projects/org.highmed/KDS/diagnose/KDS_problem_diagnose.yml |
| extension | `KDS_problem_qualifier` |  |  | CLUSTER.problem_qualifier.v2 | projects/org.highmed/KDS/diagnose/KDS_problem_qualifier.yml |
| extension | `KDS_problem_qualifier_todesursache` |  |  | CLUSTER.problem_qualifier.v2 | projects/org.highmed/KDS/todesursache/KDS_problem_qualifier.yml |
| extension | `KDS_procedure.v1` |  |  | ACTION.procedure.v1 | projects/org.highmed/KDS/procedure/KDS_procedure.v1.yml |
| extension | `KDS_structured_name.v1.person_name-structured_name` |  |  | CLUSTER.structured_name.v1 | projects/org.highmed/KDS/person/KDS_structured_name.v1.person_name-structured_name.yml |
| extension | `KDS_vital_status` | openEHR-EHR-EVALUATION.vital_status.v1 |  | EVALUATION.vital_status.v1 | projects/org.highmed/KDS/vitalstatus/KDS_vitalsigns.yml |
| extension | `eehrxf_lab_analyte` |  |  | CLUSTER.laboratory_test_analyte.v1 | projects/org.openehr/EEHRxF/lab/bundle/lab_analyte.yml |
| extension | `eehrxf_lab_result` |  |  | OBSERVATION.laboratory_test_result.v1 | projects/org.openehr/EEHRxF/lab/bundle/lab_result.yml |
| extension | `person_pseudo_cluster.v1` |  |  | CLUSTER.person.v1 | projects/org.highmed/KDS/person_pseudo/KDS_pseudo_person_cluster.yml |
| model | `ACTION.informed_consent.v0` | openEHR-EHR-ACTION.informed_consent.v0 | fhir:Consent |  | model/action/org.openehr/informed_consent.v0.yml |
| model | `ACTION.medication.v1` | openEHR-EHR-ACTION.medication.v1 | fhir:MedicationAdministration |  | model/action/org.openehr/medication.v1.yml |
| model | `ACTION.procedure.v1` | openEHR-EHR-ACTION.procedure.v1 | fhir:Procedure |  | model/action/org.openehr/procedure.v1.yml |
| model | `ADMIN_ENTRY.episode_institution_local.v0` | openEHR-EHR-ADMIN_ENTRY.episode_institution_local.v0 | fhir:Encounter |  | model/admin_entry/org.highmed/episode_institution_local.v0.yml |
| model | `ADMIN_ENTRY.person_data.v0` | openEHR-EHR-ADMIN_ENTRY.person_data.v0 | fhir:Patient |  | model/admin_entry/org.highmed/person_data.v0.yml |
| model | `ADMIN_ENTRY.versicherungsinformationen.v0` | openEHR-EHR-ADMIN_ENTRY.versicherungsinformationen.v0 | fhir:Identifier |  | model/admin_entry/org.highmed/versicherungsinformationen.v0.yml |
| model | `CLUSTER.address.v1` | openEHR-EHR-CLUSTER.address.v1 | fhir:Address |  | model/cluster/org.openehr/address.v1.yml |
| model | `CLUSTER.adverse_reaction_event.v1` | openEHR-EHR-CLUSTER.adverse_reaction_event.v1 | fhir:BackboneElement |  | model/cluster/org.openehr/adverse_reaction_event.v1.yml |
| model | `CLUSTER.anatomical_location.v1` | openEHR-EHR-CLUSTER.anatomical_location.v1 | fhir:BackboneElement |  | model/cluster/org.openehr/anatomical_location.v1.yml |
| model | `CLUSTER.case_identification.v0` | openEHR-EHR-CLUSTER.case_identification.v0 | fhir:Identifier |  | model/cluster/org.openehr/case_identification.v0.yml |
| model | `CLUSTER.clinical_evidence.v1` | openEHR-EHR-CLUSTER.clinical_evidence.v1 | fhir:BackboneElement |  | model/cluster/org.openehr/clinical_evidence.v1.yml |
| model | `CLUSTER.death_details.v1` | openEHR-EHR-CLUSTER.death_details.v1 | fhir:DateTime |  | model/cluster/org.openehr/death_details.v1.yml |
| model | `CLUSTER.device.v1` | openEHR-EHR-CLUSTER.device.v1 | fhir:Device |  | model/cluster/org.openehr/device.v1.yml |
| model | `CLUSTER.diagnosetyp.v0` | openEHR-EHR-CLUSTER.diagnosetyp.v0 | fhir:CodeableConcept |  | model/cluster/org.highmed/diagnoseTyp.yml |
| model | `CLUSTER.dosage.v2` | openEHR-EHR-CLUSTER.dosage.v2 | fhir:Dosage |  | model/cluster/org.openehr/dosage.v2.yml |
| model | `CLUSTER.dosage.v2.BackboneElement` | openEHR-EHR-CLUSTER.dosage.v2 | fhir:BackboneElement |  | model/cluster/org.openehr/dosage.v2.BackboneElement.yml |
| model | `CLUSTER.entry_category.v0` | openEHR-EHR-CLUSTER.entry_category.v0 | fhir:CodeableConcept |  | model/cluster/org.highmed/entry_category.v0.yml |
| model | `CLUSTER.identifier_fhir.v0` | openEHR-EHR-CLUSTER.identifier_fhir.v0 | fhir:Identifier |  | model/cluster/org.highmed/identifier_fhir.v0.yml |
| model | `CLUSTER.kontakttyp.v0` | openEHR-EHR-CLUSTER.kontakttyp.v0 | fhir:CodeableConcept |  | model/cluster/org.highmed/kontaktTyp.yml |
| model | `CLUSTER.laboratory_test_analyte.v1` | openEHR-EHR-CLUSTER.laboratory_test_analyte.v1 | fhir:BackboneElement |  | model/cluster/org.openehr/laboratory_test_analyte.v1.yml |
| model | `CLUSTER.lebensphase.v0` | openEHR-EHR-CLUSTER.lebensphase.v0 | fhir:CodeableConcept |  | model/cluster/org.highmed/lebensphase.v0.yml |
| model | `CLUSTER.location.v1` | openEHR-EHR-CLUSTER.location.v1 | fhir:BackboneElement |  | model/cluster/org.openehr/location.v1.yml |
| model | `CLUSTER.medication.v2` | openEHR-EHR-CLUSTER.medication.v2 | fhir:BackboneElement |  | model/cluster/org.openehr/medication.v2.yml |
| model | `CLUSTER.medication.v2.substance` | openEHR-EHR-CLUSTER.medication.v2 | fhir:Substance |  | model/cluster/org.openehr/medication.v2.substance.yml |
| model | `CLUSTER.medication_status_fhir.v0` | openEHR-EHR-CLUSTER.medication_status_fhir.v0 | fhir:CodeableConcept |  | model/cluster/org.highmed/medication_status_fhir.v0.yml |
| model | `CLUSTER.multiple_coding_icd10gm.v1` | openEHR-EHR-CLUSTER.multiple_coding_icd10gm.v1 | fhir:Coding |  | model/cluster/org.highmed/multiple_coding_icd10gm.v1.yml |
| model | `CLUSTER.observation_status_fhir.v1` | openEHR-EHR-CLUSTER.observation_status_fhir.v1 |  |  | model/cluster/org.highmed/observation_status_fhir.v1.yml |
| model | `CLUSTER.organisation.v1` | openEHR-EHR-CLUSTER.organisation.v1 | fhir:Organization |  | model/cluster/org.openehr/organisation.v1.yml |
| model | `CLUSTER.organization.v0` | openEHR-EHR-CLUSTER.organization.v0 | fhir:Coding |  | model/cluster/org.highmed/organization.v0.yml |
| model | `CLUSTER.person.v1` | openEHR-EHR-CLUSTER.person.v1 | fhir:Patient |  | model/cluster/org.openehr/person.v1.yml |
| model | `CLUSTER.person_birth_data_iso.v0` | openEHR-EHR-CLUSTER.person_birth_data_iso.v0 | fhir:BackboneElement |  | model/cluster/org.openehr/person_birth_data_iso.v0.yml |
| model | `CLUSTER.problem_qualifier.v2` | openEHR-EHR-CLUSTER.problem_qualifier.v2 | fhir:BackboneElement |  | model/cluster/org.openehr/problem_qualifier.v2.yml |
| model | `CLUSTER.specimen.v1` | openEHR-EHR-CLUSTER.specimen.v1 | fhir:BackboneElement |  | model/cluster/org.openehr/specimen.v1.yml |
| model | `CLUSTER.structured_name.v1` | openEHR-EHR-CLUSTER.structured_name.v1 | fhir:HumanName |  | model/cluster/org.openehr/structured_name.v1.yml |
| model | `CLUSTER.study_details.v1` | openEHR-EHR-CLUSTER.study_details.v1 | fhir:BackboneElement |  | model/cluster/org.highmed/study_details.v1.yml |
| model | `CLUSTER.study_participation.v1` | openEHR-EHR-CLUSTER.study_participation.v1 | fhir:BackboneElement |  | model/cluster/org.highmed/study_participation.v1.yml |
| model | `CLUSTER.timing_daily.v1` | openEHR-EHR-CLUSTER.timing_daily.v1 | fhir:Dosage |  | model/cluster/org.openehr/timing_daily.v1.yml |
| model | `CLUSTER.timing_nondaily.v1` | openEHR-EHR-CLUSTER.timing_nondaily.v1 | fhir:Dosage |  | model/cluster/org.openehr/timing_non_daily.yml |
| model | `COMPOSITION.fall.v1.encounter` | openEHR-EHR-COMPOSITION.fall.v1 | fhir:Encounter |  | model/composition/org.highmed/fall.v1.Encounter.yml |
| model | `COMPOSITION.health_summary.v1` | openEHR-EHR-COMPOSITION.health_summary.v1 | fhir:Composition |  | model/composition/org.openehr/health_summary.v1.Composition.yml |
| model | `COMPOSITION.medication_list.v1.MedicationStatement` | openEHR-EHR-COMPOSITION.medication_list.v1 | fhir:MedicationStatement |  | model/composition/org.openehr/medication_list.v1.MedicationStatement.yml |
| model | `COMPOSITION.person.v0` | openEHR-EHR-COMPOSITION.person.v0 | fhir:Patient |  | model/composition/org.highmed/person.v1.Patient.yml |
| model | `COMPOSITION.report-result.v1.Composition` | openEHR-EHR-COMPOSITION.report-result.v1 | fhir:Composition |  | model/composition/org.openehr/report-result.v1.Composition.yml |
| model | `COMPOSITION.report.v1.Condition` | openEHR-EHR-COMPOSITION.report.v1 | fhir:Condition |  | model/composition/org.openehr/report.v1.Condition.yml |
| model | `COMPOSITION.report.v1.MedicationAdministration` | openEHR-EHR-COMPOSITION.report.v1 | fhir:MedicationAdministration |  | model/composition/org.openehr/report.v1.MedicationAdministration.yml |
| model | `COMPOSITION.report.v1.Observation` | openEHR-EHR-COMPOSITION.report.v1 | fhir:Observation |  | model/composition/org.openehr/report.v1.Observation.yml |
| model | `COMPOSITION.report.v1.Procedure` | openEHR-EHR-COMPOSITION.report.v1 | fhir:Procedure |  | model/composition/org.openehr/report.v1.Procedure.yml |
| model | `COMPOSITION.report_result.v1.DiagnosticReport` | openEHR-EHR-COMPOSITION.report-result.v1 | fhir:DiagnosticReport |  | model/composition/org.openehr/report_result.v1.DiagnosticReport.yml |
| model | `COMPOSITION.request.v1.ServiceRequest` | openEHR-EHR-COMPOSITION.ServiceRequest.v1 | fhir:ServiceRequest |  | model/composition/org.openehr/request.v1.ServiceRequest.yml |
| model | `EVALUATION.absence.v2` | openEHR-EHR-EVALUATION.absence.v2 | fhir:CodeableConcept |  | model/evaluation/org.openehr/absence.v2.yml |
| model | `EVALUATION.adverse_reaction_risk.v2` | openEHR-EHR-EVALUATION.adverse_reaction_risk.v2 | fhir:AllergyIntolerance |  | model/evaluation/org.openehr/adverse_reaction_risk.v2.yml |
| model | `EVALUATION.cause_of_death.v1` | openEHR-EHR-EVALUATION.cause_of_death.v1 | fhir:Condition |  | model/evaluation/org.openehr/cause_of_death.v1.yml |
| model | `EVALUATION.device_summary.v0` | openEHR-EHR-EVALUATION.device_summary.v0 | fhir:DeviceUseStatement |  | model/evaluation/org.openehr/device_summary.v0.yml |
| model | `EVALUATION.gender.v1` | openEHR-EHR-EVALUATION.gender.v1 | fhir:Patient |  | model/evaluation/org.openehr/gender.v1.yml |
| model | `EVALUATION.problem_diagnosis.v1` | openEHR-EHR-EVALUATION.problem_diagnosis.v1 | fhir:Condition |  | model/evaluation/org.openehr/problem_diagnosis.v1.yml |
| model | `EVALUATION.vital_status.v1` | openEHR-EHR-EVALUATION.vital_status.v1 | fhir:Observation |  | model/evaluation/org.openehr/vital_status.v1.yml |
| model | `INSTRUCTION.service_request.v1` | openEHR-EHR-INSTRUCTION.service_request.v1 | fhir:ServiceRequest |  | model/instruction/org.openehr/service_request.v1.yml |
| model | `OBSERVATION.laboratory_test_result.v1` | openEHR-EHR-OBSERVATION.laboratory_test_result.v1 | fhir:DiagnosticReport |  | model/observation/org.openehr/laboratory_test_result.v1.yml |
| model | `OBSERVATION.medication_statement.v0` | openEHR-EHR-OBSERVATION.medication_statement.v0 | fhir:MedicationStatement |  | model/observation/org.openehr/medication_statement.v0.yml |
| model | `SECTION.adhoc.v1_adverse_reaction_list` | openEHR-EHR-SECTION.adhoc.v1 | fhir:BackboneElement |  | model/section/org.openehr/adhoc.v1_adverse_reaction_list.yml |
| model | `SECTION.adhoc.v1_medical_devices` | openEHR-EHR-SECTION.adhoc.v1 | fhir:BackboneElement |  | model/section/org.openehr/adhoc.v1_medical_devices.yml |
| model | `SECTION.adhoc.v1_medication_list` | openEHR-EHR-SECTION.adhoc.v1 | fhir:BackboneElement |  | model/section/org.openehr/adhoc.v1_medication_list.yml |
| model | `SECTION.adhoc.v1_problem_list` | openEHR-EHR-SECTION.adhoc.v1 | fhir:BackboneElement |  | model/section/org.openehr/adhoc.v1_problem_list.yml |

116 mappings indexed
