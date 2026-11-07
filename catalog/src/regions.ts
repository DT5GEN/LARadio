// Regions shown as tabs in the apps. One stations.json holds all of them; every station has a `region` id.
export interface Region {
  id: string;
  name: string;      // local name
  nameRu: string;    // tab label
  flag: string;
  city: string;      // default city/country label for stations
  center: [number, number];
  file: string;      // curated list (relative to catalog/)
}

export const REGIONS: Region[] = [
  { id: "la", name: "Los Angeles", nameRu: "Америка", flag: "🇺🇸", city: "Los Angeles", center: [34.05, -118.25], file: "curated.yaml" },
  { id: "es", name: "España", nameRu: "Испания", flag: "🇪🇸", city: "España", center: [40.42, -3.7], file: "curated-es.yaml" },
  { id: "se", name: "Sverige", nameRu: "Швеция", flag: "🇸🇪", city: "Sverige", center: [59.33, 18.07], file: "curated-se.yaml" },
];
